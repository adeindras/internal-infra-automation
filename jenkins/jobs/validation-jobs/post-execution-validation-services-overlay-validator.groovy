import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import hudson.plugins.git.extensions.impl.SparseCheckoutPath
import jenkins.plugins.http_request.ResponseContentSupplier
import java.security.MessageDigest

properties(
  [
    parameters([
      string(defaultValue: '', name: 'clusterName'),
      string(defaultValue: '', name: 'msaServiceData'),
      string(defaultValue: '', name: 'slackThread'),
      string(defaultValue: '', name: 'slackMessage')
    ])
  ]
)

// constants
BITBUCKET_CREDS_ID = 'bitbucket-repo-read-only'
DEPLOYMENT_REPO_SLUG = "deployments"
CLUSTER_NAME = params.clusterName
slackMessage = params.slackMessage
def msaServiceData = JsonOutput.toJson(params.msaServiceData)
def stateStatus
def latestMasterCommitHash = ""
def buildStopped = false
def slackThread = params.slackThread
def CLUSTER_PATH
def SERVICE_NAME

node("deploy-agent") {
    container('tool') {
        stage('Check Params') {
            if (CLUSTER_NAME == '') {
                currentBuild.result = 'NOT_BUILT'
                buildStopped = true
                error('clusterName is empty. Aborting the build')
            }

            if (msaServiceData == '') {
                currentBuild.result = 'NOT_BUILT'
                buildStopped = true
                error('msaServiceData is empty. Aborting the build')
            }

            if (slackMessage == '') {
                currentBuild.result = 'NOT_BUILT'
                buildStopped = true
                error('slackMessage is empty. Aborting the build')
            }

            SERVICE_NAME = sh(returnStdout: true, script: '''
                set +x
                echo "${msaServiceData}" | jq -r '.name'
            '''
            ).trim()

            CLUSTER_PATH = CLUSTER_NAME.replace('-','/')

            echo "CLUSTER_NAME: ${CLUSTER_NAME}"
            echo "SERVICE_NAME: ${SERVICE_NAME}"
            echo "CLUSTER_PATH: ${CLUSTER_PATH}"
        }
        if (!buildStopped) {
            try {
                stage('Init') {
                    createBanner("STAGE: Initializing..")

                    currentBuild.displayName = "#${BUILD_NUMBER} - ${SERVICE_NAME} - ${CLUSTER_NAME}"
                    currentBuild.result = "INPROGRESS"
                    withCredentials([string(credentialsId: "ab-deploy-automation-slackbot-token", variable: 'slackToken')]) {
                        updateSlackStatus(SERVICE_NAME, currentBuild.result, slackToken)
                    }
                }
                stage('Checkout cluster information') {
                    createBanner("STAGE: Checkout cluster ${CLUSTER_NAME} information")
                    withCredentials([string(credentialsId: "internal-deploy-tool-token-0", variable: 'bbAccessToken')]) {
                        def cmd = '''
                            # get latest commit from master
                            LATEST_MASTER_COMMIT_HASH="$(curl -sXGET -H "Authorization: Bearer ${bbAccessToken}" "https://api.bitbucket.org/2.0/repositories/accelbyte/deployments/commits/master?pagelen=1" | jq -r '.values[0].hash')"
                            echo ${LATEST_MASTER_COMMIT_HASH}
                        '''
                        latestMasterCommitHash = sh(returnStdout: true, script: cmd).trim()
                    }
                    dir("deployments${BUILD_NUMBER}") {
                        withCredentials([
                            string(credentialsId: 'internal-deploy-tool-token-0', variable: 'TOKEN_0'),
                            string(credentialsId: 'internal-deploy-tool-token-1', variable: 'TOKEN_1'),
                            string(credentialsId: 'internal-deploy-tool-token-2', variable: 'TOKEN_2'),
                            string(credentialsId: 'internal-deploy-tool-token-3', variable: 'TOKEN_3')
                        ]) {
                            def buildNo = BUILD_NUMBER as int
                            def mod = (buildNo % 4)
                            String[] tokens = [TOKEN_0, TOKEN_1, TOKEN_2, TOKEN_3]
                            env.BITBUCKET_ACCESS_TOKEN=tokens[mod]
                            sh """
                                bitbucket-downloader -f $CLUSTER_PATH/cluster-information.env \
                                    -r ${latestMasterCommitHash} \
                                    -s ${DEPLOYMENT_REPO_SLUG} \
                                    -o \$(pwd)
                            """
                        }
                    }
                }
                stage('Get cluster information') {
                    createBanner("STAGE: Get cluster information")
                    // get from file cluster-information.env in each cluster directory
                    dir("deployments${BUILD_NUMBER}/$CLUSTER_PATH") {
                        def fileContent = readFile('cluster-information.env').trim()
                        def lines = fileContent.tokenize('\n')
                        lines.each { line ->
                            def (key, value) = line.tokenize('=')
                            env."${key}" = "${value}"
                            echo "${key} = ${value}"
                        }
                    }
                }
                stage("Generate Kubeconfig") {
                    createBanner("STAGE: Assuming AWS role and Generate Kubeconfig")
                    sh """#!/bin/bash
                        set -e
                        set -o pipefail
                        envsubst < ~/.aws/config.template > ~/.aws/config
                        # aws sts get-caller-identity
                        aws eks update-kubeconfig --name ${env.EKS_CLUSTER_NAME} --region ${env.AWS_REGION}
                    """
                }
                stage("Validate Service State") {
                    createBanner("STAGE: Validating Service State")
                    dir("deployments${BUILD_NUMBER}") {
                        def cmd = """#!/bin/bash
                            set +x
                            serviceName=${SERVICE_NAME}
                            msaData=${msaServiceData}
                            dataName=()
                            expectedValue=()
                            resultValue=()
                            statusValue=()
                            dataName2=()
                            expectedValue2=()
                            resultValue2=()
                            statusValue2=()
                            stateStatus="MATCH"

                            convertMemorySize() {
                                local size=\$1

                                # Extract the numerical part and the unit part
                                local value=\$(echo "\$size" | sed -n 's/^\\([0-9]*\\).*/\\1/p')
                                local unit=\$(echo "\$size" | sed -n 's/^[0-9]*\\([A-Za-z]*\\)\$/\\1/p')

                                case \$unit in
                                    Gi)
                                        value=\$(echo "\$value * 1024" | bc)
                                        unit=Mi
                                        ;;
                                    *)
                                        echo "Unknown unit: \$unit"
                                        return 1
                                        ;;
                                esac

                                echo "\$value\$unit"
                            }

                            convertCpuSize() {
                                local size=\$1
                                local value=\$(echo "\$size * 1000" | bc)
                                local unit="m"

                                echo "\$value\$unit"
                            }

                            validateServices() {
                                # file: deployment.yaml

                                # validate cpu req
                                if [[ \$serviceName == "analytics-configuration-service" ]] \
                                    || [[ \$serviceName == "analytics-kafka-connect" ]] \
                                    || [[ \$serviceName == "abversion-exporter" ]] \
                                    || [[ \$serviceName == "analytics-game-telemetry-api" ]] \
                                    || [[ \$serviceName == "analytics-game-telemetry-monitoring" ]]; then
                                    actualServiceCpuReq=\$(jq --arg SERVICE_NAME \${serviceName} -r '.spec.containers[] | select(.name == \$SERVICE_NAME).resources.requests.cpu' actualPod.json)
                                elif [[ \$serviceName == "analytics-airflow-scheduler" ]]; then
                                    actualServiceCpuReq=\$(jq -r '.spec.containers[] | select(.name == "airflow-scheduler").resources.requests.cpu' actualPod.json)
                                elif [[ \$serviceName == "analytics-airflow-web" ]]; then
                                    actualServiceCpuReq=\$(jq -r '.spec.containers[] | select(.name == "airflow-web").resources.requests.cpu' actualPod.json)
                                else
                                    actualServiceCpuReq=\$(jq -r '.spec.containers[] | select(.name == "service").resources.requests.cpu' actualPod.json)
                                fi

                                if [[ "\$actualServiceCpuReq" == "" ]]; then
                                    actualServiceCpuReq="null"
                                fi

                                if [[ \$actualServiceCpuReq != "null" ]]; then
                                    IS_CORE=\$(echo "\$actualServiceCpuReq" | grep "m" > /dev/null; echo \$?)

                                    if [[ "\$IS_CORE" == "1" ]]; then
                                        actualServiceCpuReq=\$(convertCpuSize \$actualServiceCpuReq)
                                    fi
                                fi

                                desiredServiceCpuReq=\$(jq -r '.cpu_req' msa.json)

                                if [[ "\$desiredServiceCpuReq" == "N" ]]; then
                                    desiredServiceCpuReq="null"
                                fi

                                IS_MATCH=\$(assertCorrectness \$actualServiceCpuReq \$desiredServiceCpuReq)

                                dataName+=("Service CPU Request")
                                expectedValue+=("\$desiredServiceCpuReq")
                                resultValue+=("\$actualServiceCpuReq")

                                if [[ \$IS_MATCH == "NOT_MATCH" ]]; then
                                    statusValue+=("NOT OK")
                                elif [[ \$IS_MATCH == "MATCH" ]]; then
                                    statusValue+=("OK")
                                else
                                    statusValue+=("ERROR DURING CHECK")
                                fi

                                # validate memory req
                                if [[ \$serviceName == "analytics-configuration-service" ]] \
                                    || [[ \$serviceName == "analytics-kafka-connect" ]] \
                                    || [[ \$serviceName == "abversion-exporter" ]] \
                                    || [[ \$serviceName == "analytics-game-telemetry-api" ]] \
                                    || [[ \$serviceName == "analytics-game-telemetry-monitoring" ]]; then
                                    actualServiceMemoryReq=\$(jq --arg SERVICE_NAME \${serviceName} -r '.spec.containers[] | select(.name == \$SERVICE_NAME).resources.requests.memory' actualPod.json)
                                elif [[ \$serviceName == "analytics-airflow-scheduler" ]]; then
                                    actualServiceMemoryReq=\$(jq -r '.spec.containers[] | select(.name == "airflow-scheduler").resources.requests.memory' actualPod.json)
                                elif [[ \$serviceName == "analytics-airflow-web" ]]; then
                                    actualServiceMemoryReq=\$(jq -r '.spec.containers[] | select(.name == "airflow-web").resources.requests.memory' actualPod.json)
                                else
                                    actualServiceMemoryReq=\$(jq -r '.spec.containers[] | select(.name == "service").resources.requests.memory' actualPod.json)
                                fi

                                if [[ "\$actualServiceMemoryReq" == "" ]]; then
                                    actualServiceMemoryReq="null"
                                fi

                                if [[ \$actualServiceMemoryReq != "null" ]]; then
                                    IS_GIBIBYTE=\$(echo "\$actualServiceMemoryReq" | grep "Gi" > /dev/null; echo \$?)

                                    if [[ "\$IS_GIBIBYTE" == "0" ]]; then
                                        actualServiceMemoryReq=\$(convertMemorySize \$actualServiceMemoryReq)
                                    fi
                                fi

                                desiredServiceMemoryReq=\$(jq -r '.memory_req' msa.json)

                                if [[ "\$desiredServiceMemoryReq" == "N" ]]; then
                                    desiredServiceMemoryReq="null"
                                fi

                                IS_MATCH=\$(assertCorrectness \$actualServiceMemoryReq \$desiredServiceMemoryReq)

                                dataName+=("Service Memory Request")
                                expectedValue+=("\$desiredServiceMemoryReq")
                                resultValue+=("\$actualServiceMemoryReq")

                                if [[ \$IS_MATCH == "NOT_MATCH" ]]; then
                                    statusValue+=("NOT OK")
                                elif [[ \$IS_MATCH == "MATCH" ]]; then
                                    statusValue+=("OK")
                                else
                                    statusValue+=("ERROR DURING CHECK")
                                fi

                                # validate cpu limits
                                if [[ \$serviceName == "analytics-configuration-service" ]] \
                                    || [[ \$serviceName == "analytics-kafka-connect" ]] \
                                    || [[ \$serviceName == "abversion-exporter" ]] \
                                    || [[ \$serviceName == "analytics-game-telemetry-api" ]] \
                                    || [[ \$serviceName == "analytics-game-telemetry-monitoring" ]]; then
                                    actualServiceCpuLimit=\$(jq --arg SERVICE_NAME \${serviceName} -r '.spec.containers[] | select(.name == \$SERVICE_NAME).resources.limits.cpu' actualPod.json)
                                elif [[ \$serviceName == "analytics-airflow-scheduler" ]]; then
                                    actualServiceCpuLimit=\$(jq -r '.spec.containers[] | select(.name == "airflow-scheduler").resources.limits.cpu' actualPod.json)
                                elif [[ \$serviceName == "analytics-airflow-web" ]]; then
                                    actualServiceCpuLimit=\$(jq -r '.spec.containers[] | select(.name == "airflow-web").resources.limits.cpu' actualPod.json)
                                else
                                    actualServiceCpuLimit=\$(jq -r '.spec.containers[] | select(.name == "service").resources.limits.cpu' actualPod.json)
                                fi

                                if [[ "\$actualServiceCpuLimit" == "" ]]; then
                                    actualServiceCpuLimit="null"
                                fi

                                if [[ \$actualServiceCpuLimit != "null" ]]; then
                                    IS_CORE=\$(echo "\$actualServiceCpuLimit" | grep "m" > /dev/null; echo \$?)

                                    if [[ "\$IS_CORE" == "1" ]]; then
                                        actualServiceCpuLimit=\$(convertCpuSize \$actualServiceCpuLimit)
                                    fi
                                fi

                                desiredServiceCpuLimit=\$(jq -r '.cpu_limit' msa.json)

                                if [[ "\$desiredServiceCpuLimit" == "N" ]]; then
                                    desiredServiceCpuLimit="null"
                                fi

                                IS_MATCH=\$(assertCorrectness \$actualServiceCpuLimit \$desiredServiceCpuLimit)

                                dataName+=("Service CPU Limit")
                                expectedValue+=("\$desiredServiceCpuLimit")
                                resultValue+=("\$actualServiceCpuLimit")

                                if [[ \$IS_MATCH == "NOT_MATCH" ]]; then
                                    statusValue+=("NOT OK")
                                elif [[ \$IS_MATCH == "MATCH" ]]; then
                                    statusValue+=("OK")
                                else
                                    statusValue+=("ERROR DURING CHECK")
                                fi

                                # validate memory limits
                                if [[ \$serviceName == "analytics-configuration-service" ]] \
                                    || [[ \$serviceName == "analytics-kafka-connect" ]] \
                                    || [[ \$serviceName == "abversion-exporter" ]] \
                                    || [[ \$serviceName == "analytics-game-telemetry-api" ]] \
                                    || [[ \$serviceName == "analytics-game-telemetry-monitoring" ]]; then
                                    actualServiceMemoryLimit=\$(jq --arg SERVICE_NAME \${serviceName} -r '.spec.containers[] | select(.name == \$SERVICE_NAME).resources.limits.memory' actualPod.json)
                                elif [[ \$serviceName == "analytics-airflow-scheduler" ]]; then
                                    actualServiceMemoryLimit=\$(jq -r '.spec.containers[] | select(.name == "airflow-scheduler").resources.limits.memory' actualPod.json)
                                elif [[ \$serviceName == "analytics-airflow-web" ]]; then
                                    actualServiceMemoryLimit=\$(jq -r '.spec.containers[] | select(.name == "airflow-web").resources.limits.memory' actualPod.json)
                                else
                                    actualServiceMemoryLimit=\$(jq -r '.spec.containers[] | select(.name == "service").resources.limits.memory' actualPod.json)
                                fi

                                if [[ "\$actualServiceMemoryLimit" == "" ]]; then
                                    actualServiceMemoryLimit="null"
                                fi

                                if [[ \$actualServiceMemoryLimit != "null" ]]; then
                                    IS_GIBIBYTE=\$(echo "\$actualServiceMemoryLimit" | grep "Gi" > /dev/null; echo \$?)

                                    if [[ "\$IS_GIBIBYTE" == "0" ]]; then
                                        actualServiceMemoryLimit=\$(convertMemorySize \$actualServiceMemoryLimit)
                                    fi
                                fi

                                desiredServiceMemoryLimit=\$(jq -r '.memory_limit' msa.json)

                                if [[ "\$desiredServiceMemoryLimit" == "N" ]]; then
                                    desiredServiceMemoryLimit=\$desiredServiceMemoryReq
                                fi

                                IS_MATCH=\$(assertCorrectness \$actualServiceMemoryLimit \$desiredServiceMemoryLimit)

                                dataName+=("Service Memory Limit")
                                expectedValue+=("\$desiredServiceMemoryLimit")
                                resultValue+=("\$actualServiceMemoryLimit")

                                if [[ \$IS_MATCH == "NOT_MATCH" ]]; then
                                    statusValue+=("NOT OK")
                                elif [[ \$IS_MATCH == "MATCH" ]]; then
                                    statusValue+=("OK")
                                else
                                    statusValue+=("ERROR DURING CHECK")
                                fi
                            }

                            validateServicesAnalyticsGameTelemetryWorker() {
                                # file: deployment.yaml

                                # validate cpu req
                                actualServiceWorkerKafkaCpuReq=\$(jq -r '.spec.containers[] | select(.name == "worker-kafka").resources.requests.cpu' actualPod.json)
                                actualServiceWorkerElasticsearchCpuReq=\$(jq -r '.spec.containers[] | select(.name == "worker-elasticsearch").resources.requests.cpu' actualPod.json)

                                if [[ "\$actualServiceWorkerKafkaCpuReq" == "" ]]; then
                                    actualServiceWorkerKafkaCpuReq="null"
                                fi

                                if [[ "\$actualServiceWorkerElasticsearchCpuReq" == "" ]]; then
                                    actualServiceWorkerElasticsearchCpuReq="null"
                                fi

                                if [[ \$actualServiceWorkerKafkaCpuReq != "null" ]]; then
                                    IS_CORE=\$(echo "\$actualServiceWorkerKafkaCpuReq" | grep "m" > /dev/null; echo \$?)

                                    if [[ "\$IS_CORE" == "1" ]]; then
                                        actualServiceWorkerKafkaCpuReq=\$(convertCpuSize \$actualServiceWorkerKafkaCpuReq)
                                    fi
                                fi

                                if [[ \$actualServiceWorkerElasticsearchCpuReq != "null" ]]; then
                                    IS_CORE=\$(echo "\$actualServiceWorkerElasticsearchCpuReq" | grep "m" > /dev/null; echo \$?)

                                    if [[ "\$IS_CORE" == "1" ]]; then
                                        actualServiceWorkerElasticsearchCpuReq=\$(convertCpuSize \$actualServiceWorkerElasticsearchCpuReq)
                                    fi
                                fi

                                desiredServiceCpuReq=\$(jq -r '.cpu_req' msa.json)

                                if [[ "\$desiredServiceCpuReq" == "N" ]]; then
                                    desiredServiceCpuReq="null"
                                fi

                                IS_WORKER_KAFKA_MATCH=\$(assertCorrectness \$actualServiceWorkerKafkaCpuReq \$desiredServiceCpuReq)
                                IS_WORKER_ES_MATCH=\$(assertCorrectness \$actualServiceWorkerElasticsearchCpuReq \$desiredServiceCpuReq)

                                dataName+=("Service CPU Request")
                                expectedValue+=("\$desiredServiceCpuReq")
                                resultValue+=("\$actualServiceWorkerKafkaCpuReq")

                                dataName2+=("Service CPU Request")
                                expectedValue2+=("\$desiredServiceCpuReq")
                                resultValue2+=("\$actualServiceWorkerElasticsearchCpuReq")

                                if [[ \$IS_WORKER_KAFKA_MATCH == "NOT_MATCH" ]]; then
                                    statusValue+=("NOT OK")
                                elif [[ \$IS_WORKER_KAFKA_MATCH == "MATCH" ]]; then
                                    statusValue+=("OK")
                                else
                                    statusValue+=("ERROR DURING CHECK")
                                fi

                                if [[ \$IS_WORKER_ES_MATCH == "NOT_MATCH" ]]; then
                                    statusValue2+=("NOT OK")
                                elif [[ \$IS_WORKER_ES_MATCH == "MATCH" ]]; then
                                    statusValue2+=("OK")
                                else
                                    statusValue2+=("ERROR DURING CHECK")
                                fi

                                # validate memory req                                
                                actualServiceWorkerKafkaMemoryReq=\$(jq -r '.spec.containers[] | select(.name == "worker-kafka").resources.requests.memory' actualPod.json)
                                actualServiceWorkerElasticsearchMemoryReq=\$(jq -r '.spec.containers[] | select(.name == "worker-elasticsearch").resources.requests.memory' actualPod.json)

                                if [[ "\$actualServiceWorkerKafkaMemoryReq" == "" ]]; then
                                    actualServiceWorkerKafkaMemoryReq="null"
                                fi

                                if [[ "\$actualServiceWorkerElasticsearchMemoryReq" == "" ]]; then
                                    actualServiceWorkerElasticsearchMemoryReq="null"
                                fi

                                if [[ \$actualServiceWorkerKafkaMemoryReq != "null" ]]; then
                                    IS_GIBIBYTE=\$(echo "\$actualServiceWorkerKafkaMemoryReq" | grep "Gi" > /dev/null; echo \$?)

                                    if [[ "\$IS_GIBIBYTE" == "0" ]]; then
                                        actualServiceWorkerKafkaMemoryReq=\$(convertMemorySize \$actualServiceWorkerKafkaMemoryReq)
                                    fi
                                fi

                                if [[ \$actualServiceWorkerElasticsearchMemoryReq != "null" ]]; then
                                    IS_GIBIBYTE=\$(echo "\$actualServiceWorkerElasticsearchMemoryReq" | grep "Gi" > /dev/null; echo \$?)

                                    if [[ "\$IS_GIBIBYTE" == "0" ]]; then
                                        actualServiceWorkerElasticsearchMemoryReq=\$(convertMemorySize \$actualServiceWorkerElasticsearchMemoryReq)
                                    fi
                                fi

                                desiredServiceMemoryReq=\$(jq -r '.memory_req' msa.json)

                                if [[ "\$desiredServiceMemoryReq" == "N" ]]; then
                                    desiredServiceMemoryReq="null"
                                fi

                                IS_WORKER_KAFKA_MATCH=\$(assertCorrectness \$actualServiceWorkerKafkaMemoryReq \$desiredServiceMemoryReq)
                                IS_WORKER_ES_MATCH=\$(assertCorrectness \$actualServiceWorkerElasticsearchMemoryReq \$desiredServiceMemoryReq)

                                dataName+=("Service Memory Request")
                                expectedValue+=("\$desiredServiceMemoryReq")
                                resultValue+=("\$actualServiceWorkerKafkaMemoryReq")

                                dataName2+=("Service Memory Request")
                                expectedValue2+=("\$desiredServiceMemoryReq")
                                resultValue2+=("\$actualServiceWorkerElasticsearchMemoryReq")

                                if [[ \$IS_WORKER_KAFKA_MATCH == "NOT_MATCH" ]]; then
                                    statusValue+=("NOT OK")
                                elif [[ \$IS_WORKER_KAFKA_MATCH == "MATCH" ]]; then
                                    statusValue+=("OK")
                                else
                                    statusValue+=("ERROR DURING CHECK")
                                fi

                                if [[ \$IS_WORKER_ES_MATCH == "NOT_MATCH" ]]; then
                                    statusValue2+=("NOT OK")
                                elif [[ \$IS_WORKER_ES_MATCH == "MATCH" ]]; then
                                    statusValue2+=("OK")
                                else
                                    statusValue2+=("ERROR DURING CHECK")
                                fi

                                # validate cpu limits
                                actualServiceWorkerKafkaCpuLimit=\$(jq -r '.spec.containers[] | select(.name == "worker-kafka").resources.limits.cpu' actualPod.json)
                                actualServiceWorkerElasticsearchCpuLimit=\$(jq -r '.spec.containers[] | select(.name == "worker-elasticsearch").resources.limits.cpu' actualPod.json)

                                if [[ "\$actualServiceWorkerKafkaCpuLimit" == "" ]]; then
                                    actualServiceWorkerKafkaCpuLimit="null"
                                fi

                                if [[ "\$actualServiceWorkerElasticsearchCpuLimit" == "" ]]; then
                                    actualServiceWorkerElasticsearchCpuLimit="null"
                                fi

                                if [[ \$actualServiceWorkerKafkaCpuLimit != "null" ]]; then
                                    IS_CORE=\$(echo "\$actualServiceWorkerKafkaCpuLimit" | grep "m" > /dev/null; echo \$?)

                                    if [[ "\$IS_CORE" == "1" ]]; then
                                        actualServiceWorkerKafkaCpuLimit=\$(convertCpuSize \$actualServiceWorkerKafkaCpuLimit)
                                    fi
                                fi

                                if [[ \$actualServiceWorkerElasticsearchCpuLimit != "null" ]]; then
                                    IS_CORE=\$(echo "\$actualServiceWorkerElasticsearchCpuLimit" | grep "m" > /dev/null; echo \$?)

                                    if [[ "\$IS_CORE" == "1" ]]; then
                                        actualServiceWorkerElasticsearchCpuLimit=\$(convertCpuSize \$actualServiceWorkerElasticsearchCpuLimit)
                                    fi
                                fi

                                desiredServiceCpuLimit=\$(jq -r '.cpu_limit' msa.json)

                                if [[ "\$desiredServiceCpuLimit" == "N" ]]; then
                                    desiredServiceCpuLimit="null"
                                fi

                                IS_WORKER_KAFKA_MATCH=\$(assertCorrectness \$actualServiceWorkerKafkaCpuLimit \$desiredServiceCpuLimit)
                                IS_WORKER_ES_MATCH=\$(assertCorrectness \$actualServiceWorkerElasticsearchCpuLimit \$desiredServiceCpuLimit)

                                dataName+=("Service CPU Limit")
                                expectedValue+=("\$desiredServiceCpuLimit")
                                resultValue+=("\$actualServiceWorkerKafkaCpuLimit")

                                dataName2+=("Service CPU Limit")
                                expectedValue2+=("\$desiredServiceCpuLimit")
                                resultValue2+=("\$actualServiceWorkerElasticsearchCpuLimit")

                                if [[ \$IS_WORKER_KAFKA_MATCH == "NOT_MATCH" ]]; then
                                    statusValue+=("NOT OK")
                                elif [[ \$IS_WORKER_KAFKA_MATCH == "MATCH" ]]; then
                                    statusValue+=("OK")
                                else
                                    statusValue+=("ERROR DURING CHECK")
                                fi

                                if [[ \$IS_WORKER_ES_MATCH == "NOT_MATCH" ]]; then
                                    statusValue2+=("NOT OK")
                                elif [[ \$IS_WORKER_ES_MATCH == "MATCH" ]]; then
                                    statusValue2+=("OK")
                                else
                                    statusValue2+=("ERROR DURING CHECK")
                                fi

                                # validate memory limits
                                actualServiceWorkerKafkaMemoryLimit=\$(jq -r '.spec.containers[] | select(.name == "worker-kafka").resources.limits.memory' actualPod.json)
                                actualServiceWorkerElasticsearchMemoryLimit=\$(jq -r '.spec.containers[] | select(.name == "worker-elasticsearch").resources.limits.memory' actualPod.json)

                                if [[ "\$actualServiceWorkerKafkaMemoryLimit" == "" ]]; then
                                    actualServiceWorkerKafkaMemoryLimit="null"
                                fi

                                if [[ "\$actualServiceWorkerElasticsearchMemoryLimit" == "" ]]; then
                                    actualServiceWorkerElasticsearchMemoryLimit="null"
                                fi

                                if [[ \$actualServiceWorkerKafkaMemoryLimit != "null" ]]; then
                                    IS_GIBIBYTE=\$(echo "\$actualServiceWorkerKafkaMemoryLimit" | grep "Gi" > /dev/null; echo \$?)

                                    if [[ "\$IS_GIBIBYTE" == "0" ]]; then
                                        actualServiceWorkerKafkaMemoryLimit=\$(convertMemorySize \$actualServiceWorkerKafkaMemoryLimit)
                                    fi
                                fi

                                if [[ \$actualServiceWorkerElasticsearchMemoryLimit != "null" ]]; then
                                    IS_GIBIBYTE=\$(echo "\$actualServiceWorkerElasticsearchMemoryLimit" | grep "Gi" > /dev/null; echo \$?)

                                    if [[ "\$IS_GIBIBYTE" == "0" ]]; then
                                        actualServiceWorkerElasticsearchMemoryLimit=\$(convertMemorySize \$actualServiceWorkerElasticsearchMemoryLimit)
                                    fi
                                fi

                                desiredServiceMemoryLimit=\$(jq -r '.memory_limit' msa.json)

                                if [[ "\$desiredServiceMemoryLimit" == "N" ]]; then
                                    desiredServiceMemoryLimit="null"
                                fi

                                IS_WORKER_KAFKA_MATCH=\$(assertCorrectness \$actualServiceWorkerKafkaMemoryLimit \$desiredServiceMemoryLimit)
                                IS_WORKER_ES_MATCH=\$(assertCorrectness \$actualServiceWorkerElasticsearchMemoryLimit \$desiredServiceMemoryLimit)

                                dataName+=("Service Memory Limit")
                                expectedValue+=("\$desiredServiceMemoryLimit")
                                resultValue+=("\$actualServiceWorkerKafkaMemoryLimit")

                                dataName2+=("Service Memory Limit")
                                expectedValue2+=("\$desiredServiceMemoryLimit")
                                resultValue2+=("\$actualServiceWorkerElasticsearchMemoryLimit")

                                if [[ \$IS_WORKER_KAFKA_MATCH == "NOT_MATCH" ]]; then
                                    statusValue+=("NOT OK")
                                elif [[ \$IS_WORKER_KAFKA_MATCH == "MATCH" ]]; then
                                    statusValue+=("OK")
                                else
                                    statusValue+=("ERROR DURING CHECK")
                                fi

                                if [[ \$IS_WORKER_ES_MATCH == "NOT_MATCH" ]]; then
                                    statusValue2+=("NOT OK")
                                elif [[ \$IS_WORKER_ES_MATCH == "MATCH" ]]; then
                                    statusValue2+=("OK")
                                else
                                    statusValue2+=("ERROR DURING CHECK")
                                fi
                            }

                            validateLinkerdQoS() {
                                # file: infrastructure/karpenter-deployment.yaml

                                # validate cpu req
                                actualMeshCpuReq=\$(jq -r '.spec.containers[] | select(.name == "linkerd-proxy").resources.requests.cpu' actualPod.json)

                                if [[ "\$actualMeshCpuReq" == "" ]]; then
                                    actualMeshCpuReq="null"
                                fi

                                if [[ \$actualMeshCpuReq != "null" ]]; then
                                    IS_CORE=\$(echo "\$actualMeshCpuReq" | grep "m" > /dev/null; echo \$?)

                                    if [[ "\$IS_CORE" == "1" ]]; then
                                        actualMeshCpuReq=\$(convertCpuSize \$actualMeshCpuReq)
                                    fi
                                fi

                                desiredMeshCpuReq=\$(jq -r '.mesh_cpu_req' msa.json)

                                if [[ "\$desiredMeshCpuReq" == "N" ]]; then
                                    desiredMeshCpuReq="null"
                                fi

                                IS_MATCH=\$(assertCorrectness \$actualMeshCpuReq \$desiredMeshCpuReq)

                                dataName+=("Linkerd Proxy CPU Request")
                                expectedValue+=("\$desiredMeshCpuReq")
                                resultValue+=("\$actualMeshCpuReq")

                                if [[ \$IS_MATCH == "NOT_MATCH" ]]; then
                                    statusValue+=("NOT OK")
                                elif [[ \$IS_MATCH == "MATCH" ]]; then
                                    statusValue+=("OK")
                                else
                                    statusValue+=("ERROR DURING CHECK")
                                fi

                                # validate memory req
                                actualMeshMemoryReq=\$(jq -r '.spec.containers[] | select(.name == "linkerd-proxy").resources.requests.memory' actualPod.json)

                                if [[ "\$actualMeshMemoryReq" == "" ]]; then
                                    actualMeshMemoryReq="null"
                                fi

                                if [[ \$actualMeshMemoryReq != "null" ]]; then
                                    IS_GIBIBYTE=\$(echo "\$actualMeshMemoryReq" | grep "Gi" > /dev/null; echo \$?)

                                    if [[ "\$IS_GIBIBYTE" == "0" ]]; then
                                        actualMeshMemoryReq=\$(convertMemorySize \$actualMeshMemoryReq)
                                    fi
                                fi

                                desiredMeshMemoryReq=\$(jq -r '.mesh_memory_req' msa.json)

                                if [[ "\$desiredMeshMemoryReq" == "N" ]]; then
                                    desiredMeshMemoryReq="null"
                                fi

                                IS_MATCH=\$(assertCorrectness \$actualMeshMemoryReq \$desiredMeshMemoryReq)

                                dataName+=("Linkerd Proxy Memory Request")
                                expectedValue+=("\$desiredMeshMemoryReq")
                                resultValue+=("\$actualMeshMemoryReq")

                                if [[ \$IS_MATCH == "NOT_MATCH" ]]; then
                                    statusValue+=("NOT OK")
                                elif [[ \$IS_MATCH == "MATCH" ]]; then
                                    statusValue+=("OK")
                                else
                                    statusValue+=("ERROR DURING CHECK")
                                fi

                                # validate cpu limits
                                actualMeshCpuLimit=\$(jq -r '.spec.containers[] | select(.name == "linkerd-proxy").resources.limits.cpu' actualPod.json)

                                if [[ "\$actualMeshCpuLimit" == "" ]]; then
                                    actualMeshCpuLimit="null"
                                fi

                                if [[ \$actualMeshCpuLimit != "null" ]]; then
                                    IS_CORE=\$(echo "\$actualMeshCpuLimit" | grep "m" > /dev/null; echo \$?)

                                    if [[ "\$IS_CORE" == "1" ]]; then
                                        actualMeshCpuLimit=\$(convertCpuSize \$actualMeshCpuLimit)
                                    fi
                                fi

                                desiredMeshCpuLimit=\$(jq -r '.mesh_cpu_limit' msa.json)

                                if [[ "\$desiredMeshCpuLimit" == "N" ]]; then
                                    desiredMeshCpuLimit="null"
                                fi

                                IS_MATCH=\$(assertCorrectness \$actualMeshCpuLimit \$desiredMeshCpuLimit)

                                dataName+=("Linkerd Proxy CPU Limit")
                                expectedValue+=("\$desiredMeshCpuLimit")
                                resultValue+=("\$actualMeshCpuLimit")

                                if [[ \$IS_MATCH == "NOT_MATCH" ]]; then
                                    statusValue+=("NOT OK")
                                elif [[ \$IS_MATCH == "MATCH" ]]; then
                                    statusValue+=("OK")
                                else
                                    statusValue+=("ERROR DURING CHECK")
                                fi

                                # validate memory limits
                                actualMeshMemoryLimit=\$(jq -r '.spec.containers[] | select(.name == "linkerd-proxy").resources.limits.memory' actualPod.json)

                                if [[ "\$actualMeshMemoryLimit" == "" ]]; then
                                    actualMeshMemoryLimit="null"
                                fi

                                if [[ \$actualMeshMemoryLimit != "null" ]]; then
                                    IS_GIBIBYTE=\$(echo "\$actualMeshMemoryLimit" | grep "Gi" > /dev/null; echo \$?)

                                    if [[ "\$IS_GIBIBYTE" == "0" ]]; then
                                        actualMeshMemoryLimit=\$(convertMemorySize \$actualMeshMemoryLimit)
                                    fi
                                fi

                                desiredMeshMemoryLimit=\$(jq -r '.mesh_memory_limit' msa.json)

                                if [[ "\$desiredMeshMemoryLimit" == "N" ]]; then
                                    desiredMeshMemoryLimit="null"
                                fi

                                IS_MATCH=\$(assertCorrectness \$actualMeshMemoryLimit \$desiredMeshMemoryLimit)

                                dataName+=("Linkerd Proxy Memory Limit")
                                expectedValue+=("\$desiredMeshMemoryLimit")
                                resultValue+=("\$actualMeshMemoryLimit")

                                if [[ \$IS_MATCH == "NOT_MATCH" ]]; then
                                    statusValue+=("NOT OK")
                                elif [[ \$IS_MATCH == "MATCH" ]]; then
                                    statusValue+=("OK")
                                else
                                    statusValue+=("ERROR DURING CHECK")
                                fi
                            }

                            validateHpa() {
                                # file: horizontal-pod-autoscaler.yaml

                                # validate hpa min
                                actualHpaMin=\$(jq -r '.spec.minReplicas' actualHpa.json)
                                desiredHpaMin=\$(jq -r '.hpa_min' msa.json)

                                if [[ "\$actualHpaMin" == "" ]]; then
                                    actualHpaMin="null"
                                fi

                                if [[ "\$desiredHpaMin" == "N" ]]; then
                                    desiredHpaMin="null"
                                fi

                                IS_MATCH=\$(assertCorrectness \$actualHpaMin \$desiredHpaMin)

                                dataName+=("HPA Min Replica")
                                expectedValue+=("\$desiredHpaMin")
                                resultValue+=("\$actualHpaMin")

                                if [[ \$IS_MATCH == "NOT_MATCH" ]]; then
                                    statusValue+=("NOT OK")
                                elif [[ \$IS_MATCH == "MATCH" ]]; then
                                    statusValue+=("OK")
                                else
                                    statusValue+=("ERROR DURING CHECK")
                                fi

                                # validate hpa max
                                actualHpaMax=\$(jq -r '.spec.maxReplicas' actualHpa.json)
                                desiredHpaMax=\$(jq -r '.hpa_max' msa.json)

                                if [[ "\$actualHpaMax" == "" ]]; then
                                    actualHpaMax="null"
                                fi

                                if [[ "\$desiredHpaMax" == "N" ]]; then
                                    desiredHpaMax="null"
                                fi

                                IS_MATCH=\$(assertCorrectness \$actualHpaMax \$desiredHpaMax)

                                dataName+=("HPA Max Replica")
                                expectedValue+=("\$desiredHpaMax")
                                resultValue+=("\$actualHpaMax")

                                if [[ \$IS_MATCH == "NOT_MATCH" ]]; then
                                    statusValue+=("NOT OK")
                                elif [[ \$IS_MATCH == "MATCH" ]]; then
                                    statusValue+=("OK")
                                else
                                    statusValue+=("ERROR DURING CHECK")
                                fi

                                # validate hpa cpu threshold
                                actualHpaCpu=\$(jq -r '.spec.metrics[] | select(.resource.name == "cpu").resource.target.averageUtilization' actualHpa.json)
                                desiredHpaCpu=\$(jq -r '.hpa_cpu' msa.json)

                                if [[ "\$actualHpaCpu" == "" ]]; then
                                    actualHpaCpu="null"
                                fi

                                if [[ "\$desiredHpaCpu" == "N" ]]; then
                                    desiredHpaCpu="null"
                                fi

                                IS_MATCH=\$(assertCorrectness \$actualHpaCpu \$desiredHpaCpu)

                                dataName+=("HPA CPU Threshold")
                                expectedValue+=("\$desiredHpaCpu")
                                resultValue+=("\$actualHpaCpu")

                                if [[ \$IS_MATCH == "NOT_MATCH" ]]; then
                                    statusValue+=("NOT OK")
                                elif [[ \$IS_MATCH == "MATCH" ]]; then
                                    statusValue+=("OK")
                                else
                                    statusValue+=("ERROR DURING CHECK")
                                fi

                                # validate hpa memory threshold
                                actualHpaMemory=\$(jq -r '.spec.metrics[] | select(.resource.name == "memory").resource.target.averageUtilization' actualHpa.json)
                                desiredHpaMemory=\$(jq -r '.hpa_memory' msa.json)

                                if [[ "\$actualHpaMemory" == "" ]]; then
                                    actualHpaMemory="null"
                                fi

                                if [[ "\$desiredHpaMemory" == "N" ]]; then
                                    desiredHpaMemory="null"
                                fi

                                IS_MATCH=\$(assertCorrectness \$actualHpaMemory \$desiredHpaMemory)

                                dataName+=("HPA Memory Threshold")
                                expectedValue+=("\$desiredHpaMemory")
                                resultValue+=("\$actualHpaMemory")

                                if [[ \$IS_MATCH == "NOT_MATCH" ]]; then
                                    statusValue+=("NOT OK")
                                elif [[ \$IS_MATCH == "MATCH" ]]; then
                                    statusValue+=("OK")
                                else
                                    statusValue+=("ERROR DURING CHECK")
                                fi
                            }

                            validateServicesOverlay() {
                                validateServices
                                validateLinkerdQoS
                                validateHpa
                            }

                            validateServicesOverlayAnalyticsGameTelemetryWorker() {
                                validateServicesAnalyticsGameTelemetryWorker
                                validateLinkerdQoS
                                validateHpa
                            }

                            getPodJsonManifest() {
                                if [[ \${serviceName} == "analytics-airflow-scheduler" ]] || [[ \${serviceName} == "analytics-airflow-web" ]]; then
                                    kubectl -n justice get po \
                                        \$(kubectl -n justice get po --no-headers -o custom-columns=":metadata.name" \
                                            | grep \${serviceName} \
                                            | grep -E '^[a-z0-9]([-a-z0-9]*[a-z0-9])?-[a-f0-9]{8,10}-[a-z0-9]{5}\$' \
                                            | head -n1 \
                                            | awk '{print \$1}') \
                                        -o json \
                                    >> actualPod.json
                                else
                                    nameCount=\${#serviceName}
                                    if [[ "\$nameCount" -gt 47 ]]; then
                                        kubectl -n justice get po \
                                            \$(kubectl -n justice get po -l app=\${serviceName} --no-headers -o custom-columns=":metadata.name" \
                                                | grep -v 'job' \
                                                | grep -v 'seeding' \
                                                | head -n1 \
                                                | awk '{print \$1}') \
                                            -o json \
                                        >> actualPod.json
                                    else
                                        kubectl -n justice get po \
                                            \$(kubectl -n justice get po -l app=\${serviceName} --no-headers -o custom-columns=":metadata.name" \
                                                | grep -E '^[a-z0-9]([-a-z0-9]*[a-z0-9])?-[a-f0-9]{8,10}-[a-z0-9]{5}\$' \
                                                | head -n1 \
                                                | awk '{print \$1}') \
                                            -o json \
                                        >> actualPod.json
                                    fi
                                fi
                            }

                            getHPAJsonManifest() {
                                if [[ \${serviceName} == "analytics-configuration-service" ]] || [[ \${serviceName} == "analytics-airflow" ]] || [[ \${serviceName} == "analytics-game-telemetry" ]] || [[ \${serviceName} == "analytics-kafka-connect" ]] || [[ \${serviceName} == "abversion-exporter" ]]; then
                                    kubectl -n justice get hpa \${serviceName}
                                    if [[ \$? -eq 0 ]]; then
                                        kubectl -n justice get hpa \${serviceName} -o json >> actualHpa.json
                                    else
                                        echo "{}" >> actualHpa.json
                                    fi
                                else
                                    kubectl -n justice get hpa \${serviceName} -o json >> actualHpa.json
                                fi
                            }

                            getMsaData() {
                                echo \${msaData} >> msa.json
                            }

                            assertCorrectness() {
                                actualState=\$1
                                desiredState=\$2

                                if [[ \${actualState} != "\${desiredState}" ]]; then
                                    echo "NOT_MATCH"
                                else
                                    echo "MATCH"
                                fi
                            }

                            writeTableToFile() {
                                local fileName=\$1
                                local serviceName=\$2
                                local -n dataNames=\$3
                                local -n expectedValues=\$4
                                local -n resultValues=\$5
                                local -n statusValues=\$6

                                # Calculate the maximum length of each column
                                local maxDataNameColumnLength=0
                                local maxExpectedColumnLength=8 # Minimum length to fit the column header
                                local maxResultColumnLength=6   # Minimum length to fit the column header
                                local maxStatusColumnLength=6   # Minimum length to fit the column header

                                for ((i = 0; i < \${#dataNames[@]}; i++)); do
                                    maxDataNameColumnLength=\$((\${#dataNames[i]} > maxDataNameColumnLength ? \${#dataNames[i]} : maxDataNameColumnLength))
                                    maxExpectedColumnLength=\$((\${#expectedValues[i]} > maxExpectedColumnLength ? \${#expectedValues[i]} : maxExpectedColumnLength))
                                    maxResultColumnLength=\$((\${#resultValues[i]} > maxResultColumnLength ? \${#resultValues[i]} : maxResultColumnLength))
                                    maxStatusColumnLength=\$((\${#statusValues[i]} > maxStatusColumnLength ? \${#statusValues[i]} : maxStatusColumnLength))
                                done

                                # Function to print a row
                                printRow() {
                                    printf "| %-*s | %-*s | %-*s | %-*s |\n" \
                                        "\$maxDataNameColumnLength" "\$1" \
                                        "\$maxExpectedColumnLength" "\$2" \
                                        "\$maxResultColumnLength" "\$3" \
                                        "\$maxStatusColumnLength" "\$4"
                                }

                                # Function to print a separator
                                printSeparator() {
                                    printf "+-%-*s-+-%-*s-+-%-*s-+-%-*s-+\n" \
                                        "\$maxDataNameColumnLength" "\$(head -c "\$maxDataNameColumnLength" </dev/zero | tr '\\0' '-')" \
                                        "\$maxExpectedColumnLength" "\$(head -c "\$maxExpectedColumnLength" </dev/zero | tr '\\0' '-')" \
                                        "\$maxResultColumnLength" "\$(head -c "\$maxResultColumnLength" </dev/zero | tr '\\0' '-')" \
                                        "\$maxStatusColumnLength" "\$(head -c "\$maxStatusColumnLength" </dev/zero | tr '\\0' '-')"
                                }

                                printSignature() {
                                    echo "Generated with ❤︎ by ReleaseOps Team"
                                }

                                # Write the table to the file
                                {
                                    printf "+-%-*s-+\\n" "57" "\$(head -c 57 </dev/zero | tr '\\0' '-')"
                                    printf "| Service Name: %-43s |\\n" "\$serviceName"
                                    printSeparator
                                    printRow "Data Name" "Expected" "Result" "Status"
                                    printSeparator

                                    for ((i = 0; i < \${#dataNames[@]}; i++)); do
                                        printRow "\${dataNames[i]}" "\${expectedValues[i]}" "\${resultValues[i]}" "\${statusValues[i]}"
                                    done

                                    printSeparator
                                    printSignature
                                } >"\$fileName"
                            }

                            writeTableToFileAnalyticsGameTelemetryWorker() {
                                local fileName=\$1
                                local serviceName=\$2
                                local containerName=\$3
                                local -n dataNames=\$4
                                local -n expectedValues=\$5
                                local -n resultValues=\$6
                                local -n statusValues=\$7

                                # Calculate the maximum length of each column
                                local maxDataNameColumnLength=28 # Minimum length to fit the column header
                                local maxExpectedColumnLength=8  # Minimum length to fit the column header
                                local maxResultColumnLength=6    # Minimum length to fit the column header
                                local maxStatusColumnLength=6    # Minimum length to fit the column header

                                for ((i = 0; i < \${#dataNames[@]}; i++)); do
                                    maxDataNameColumnLength=\$((\${#dataNames[i]} > maxDataNameColumnLength ? \${#dataNames[i]} : maxDataNameColumnLength))
                                    maxExpectedColumnLength=\$((\${#expectedValues[i]} > maxExpectedColumnLength ? \${#expectedValues[i]} : maxExpectedColumnLength))
                                    maxResultColumnLength=\$((\${#resultValues[i]} > maxResultColumnLength ? \${#resultValues[i]} : maxResultColumnLength))
                                    maxStatusColumnLength=\$((\${#statusValues[i]} > maxStatusColumnLength ? \${#statusValues[i]} : maxStatusColumnLength))
                                done

                                # Function to print a row
                                printRow() {
                                    printf "| %-*s | %-*s | %-*s | %-*s |\n" \
                                        "\$maxDataNameColumnLength" "\$1" \
                                        "\$maxExpectedColumnLength" "\$2" \
                                        "\$maxResultColumnLength" "\$3" \
                                        "\$maxStatusColumnLength" "\$4"
                                }

                                # Function to print a separator
                                printSeparator() {
                                    printf "+-%-*s-+-%-*s-+-%-*s-+-%-*s-+\n" \
                                        "\$maxDataNameColumnLength" "\$(head -c "\$maxDataNameColumnLength" </dev/zero | tr '\\0' '-')" \
                                        "\$maxExpectedColumnLength" "\$(head -c "\$maxExpectedColumnLength" </dev/zero | tr '\\0' '-')" \
                                        "\$maxResultColumnLength" "\$(head -c "\$maxResultColumnLength" </dev/zero | tr '\\0' '-')" \
                                        "\$maxStatusColumnLength" "\$(head -c "\$maxStatusColumnLength" </dev/zero | tr '\\0' '-')"
                                }

                                printSignature() {
                                    echo "Generated with ❤︎ by ReleaseOps Team"
                                }

                                # Write the table to the file
                                {
                                    printf "+-%-*s-+\\n" "57" "\$(head -c 57 </dev/zero | tr '\\0' '-')"
                                    printf "| Service Name: %-43s |\\n" "\$serviceName"
                                    printf "+-%-*s-+\\n" "57" "\$(head -c 57 </dev/zero | tr '\\0' '-')"
                                    printf "| Container Name: %-41s |\\n" "\$containerName"
                                    printSeparator
                                    printRow "Data Name" "Expected" "Result" "Status"
                                    printSeparator

                                    for ((i = 0; i < \${#dataNames[@]}; i++)); do
                                        printRow "\${dataNames[i]}" "\${expectedValues[i]}" "\${resultValues[i]}" "\${statusValues[i]}"
                                    done

                                    printSeparator
                                    printSignature

                                    echo
                                } >>"\$fileName"
                            }

                            generateTable() {
                                mkdir report
                                cd report
                                writeTableToFile "diffTableReport.txt" "\$serviceName" dataName expectedValue resultValue statusValue
                            }

                            generateTableAnalyticsGameTelemetryWorker() {
                                mkdir report
                                cd report
                                writeTableToFileAnalyticsGameTelemetryWorker "diffTableReport.txt" "\$serviceName" "worker-kafka" dataName expectedValue resultValue statusValue
                                writeTableToFileAnalyticsGameTelemetryWorker "diffTableReport.txt" "\$serviceName" "worker-elasticsearch" dataName2 expectedValue2 resultValue2 statusValue2
                            }

                            checkStatus() {
                                for STATUS in "\${statusValue[@]}"; {
                                    if [[ \$STATUS != "OK" ]]; then
                                        stateStatus="NOT_MATCH"
                                        break
                                    fi
                                }

                                if [[ \$stateStatus="MATCH" ]]; then
                                    for STATUS in "\${statusValue2[@]}"; {
                                        if [[ \$STATUS != "OK" ]]; then
                                            stateStatus="NOT_MATCH"
                                            break
                                        fi
                                    }
                                fi
                            }

                            main() {
                                getPodJsonManifest
                                getHPAJsonManifest
                                getMsaData

                                if [[ \${serviceName} == "analytics-game-telemetry-worker" ]]; then
                                    validateServicesOverlayAnalyticsGameTelemetryWorker
                                    generateTableAnalyticsGameTelemetryWorker
                                else
                                    validateServicesOverlay
                                    generateTable
                                fi

                                checkStatus

                                echo "\$stateStatus"
                            }

                            main
                        """
                        stateStatus = sh(returnStdout: true, script: cmd).trim()

                        echo "stateStatus: ${stateStatus}"
                        switch(stateStatus) {
                            case "MATCH":
                                echo "Services-Overlay state is match"
                                currentBuild.result = 'SUCCESS'
                            break
                            case "NOT_MATCH":
                                echo "Services-Overlay state is not match"
                                currentBuild.result = 'FAILURE'
                            break
                            default:
                                echo "Error during state check"
                                currentBuild.result = 'ABORTED'
                            break
                        } 
                    }
                }
                stage("Generate Report") {
                    dir("deployments${BUILD_NUMBER}") {
                        publishHTML(target: [
                            reportDir               : "report",
                            reportFiles             : "diffTableReport.txt",
                            reportName              : "Diff Report",
                            alwaysLinkToLastBuild   : true,
                            allowMissing            : true,
                            keepAll                 : true 
                        ])
                    }
                }
            } catch (SkipException ex) {
                currentBuild.result = 'ABORTED'
                currentBuild.displayName = "${currentBuild.displayName} - [Skipped]"
                echo "Build is skipped:\n\t${ex.message}"
            } catch (InterruptedException err) {
                currentBuild.result = 'ABORTED'
            } catch (Exception err) {
                echo "Exception Thrown:\n\t${err}"
                currentBuild.result = 'FAILURE'
            } finally {
                stage('Notification') {
                    withCredentials([string(credentialsId: "ab-deploy-automation-slackbot-token", variable: 'slackToken')]) {
                        switch(stateStatus) {
                            case "MATCH":
                                echo "Services-Overlay state is match"
                                updateSlackStatus(SERVICE_NAME, "SUCCESS", slackToken)
                                currentBuild.result = 'SUCCESS'
                            break
                            case "NOT_MATCH":
                                echo "Services-Overlay state is not match"
                                updateSlackStatus(SERVICE_NAME, "FAILURE", slackToken)
                                currentBuild.result = 'FAILURE'
                            break
                            default:
                                echo "Error during state check"
                                updateSlackStatus(SERVICE_NAME, "ABORTED", slackToken)
                                currentBuild.result = 'ABORTED'
                            break
                        } 
                    }
                }
            }
        }
    }
}


void updateSlackStatus(String serviceName, status, token) {
    def slackChannel
    def slackEmoji
    def slackEmojiInProgress = "loading2"
    def slackEmojiSucceed = "white_check_mark"
    def slackEmojiFailure = "x"
    def slackEmojiAborted = "warning"
    def post
    def postData

    if (WORKSPACE.contains("DEVELOPMENT")) {
        slackChannel = "C07C69NHGTW"
    } else {
        slackChannel = "C079A11910R"
    }

    switch(status) {
        case "SUCCESS":
            slackEmoji = slackEmojiSucceed
            break
        case "INPROGRESS":
            slackEmoji = slackEmojiInProgress
            break
        case "FAILURE":
            slackEmoji = slackEmojiFailure
            break
        case "ABORTED":
            slackEmoji = slackEmojiAborted
            break
        default:
            throw new Exception("Unknown Jenkins build status: $status")
    }

    // POST
    post = new URL("https://slack.com/api/chat.update").openConnection();

    if (status == "SUCCESS" || status == "INPROGRESS") {
        postData =  [
            channel: slackChannel,
            blocks: [
                [
                    type: "rich_text",
                    elements: [
                        [
                            type: "rich_text_list",
                            style: "bullet",
                            elements: [
                                [
                                    type: "rich_text_section",
                                    elements: [
                                        [
                                            type: "text",
                                            text: "${serviceName}: "
                                        ],
                                        [
                                            type: "emoji",
                                            name: "${slackEmoji}"
                                        ],
                                        [
                                            type: "link",
                                            url: "${BUILD_URL}Diff_20Report/",
                                            text: " [REPORT]",
                                            style: [
                                                bold: true
                                            ]
                                        ]
                                    ]
                                ]
                            ]
                        ]
                    ]
                ]
            ],
            ts: "${slackMessage}"
        ]
    } else if (status == "FAILURE") {
        postData =  [
            channel: slackChannel,
            blocks: [
                [
                    type: "rich_text",
                    elements: [
                        [
                            type: "rich_text_list",
                            style: "bullet",
                            elements: [
                                [
                                    type: "rich_text_section",
                                    elements: [
                                        [
                                            type: "text",
                                            text: "${serviceName}: "
                                        ],
                                        [
                                            type: "emoji",
                                            name: "${slackEmoji}"
                                        ],
                                        [
                                            type: "text",
                                            text: "   ----->    "
                                        ],
                                        [
                                            type: "link",
                                            url: "${BUILD_URL}Diff_20Report/",
                                            text: " check this",
                                            style: [
                                                bold: true
                                            ]
                                        ]
                                    ]
                                ]
                            ]
                        ]
                    ]
                ]
            ],
            ts: "${slackMessage}"
        ]
    } else {
        postData =  [
            channel: slackChannel,
            blocks: [
                [
                    type: "rich_text",
                    elements: [
                        [
                            type: "rich_text_list",
                            style: "bullet",
                            elements: [
                                [
                                    type: "rich_text_section",
                                    elements: [
                                        [
                                            type: "text",
                                            text: "${serviceName}: "
                                        ],
                                        [
                                            type: "emoji",
                                            name: "${slackEmoji}"
                                        ],
                                        [
                                            type: "text",
                                            text: "   ----->    "
                                        ],
                                        [
                                            type: "link",
                                            url: "${BUILD_URL}console",
                                            text: " check this",
                                            style: [
                                                bold: true
                                            ]
                                        ]
                                    ]
                                ]
                            ]
                        ]
                    ]
                ]
            ],
            ts: "${slackMessage}"
        ]
    }

    def jsonPayload = JsonOutput.toJson(postData)
    post.setRequestMethod("POST")
    post.setDoOutput(true)
    post.setRequestProperty("Content-Type", "application/json; charset=utf-8")
    post.setRequestProperty("Authorization", "Bearer ${slackToken}")
    post.getOutputStream().write(jsonPayload.getBytes("UTF-8"));
    def postRC = post.getResponseCode();
    println(postRC);
    if(postRC.equals(200) || postRC.equals(201)) {
        println(post.getInputStream().getText())
    }
}

// Hacky way to skip later stages
public class SkipException extends Exception {
    public SkipException(String errorMessage) {
        super(errorMessage);
    }
}


void createBanner(String message) {
    ansiColor('xterm'){
        echo '\033[1;4;37;44m\033[0J --------------------------------------------------------------\033[0m'
        echo '\033[1;4;33m' + ":: ${message}"
        echo '\033[1;4;37;44m\033[0J --------------------------------------------------------------\033[0m'
    }
}