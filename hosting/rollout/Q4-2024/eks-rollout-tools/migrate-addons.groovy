	import groovy.json.JsonOutput
	import groovy.json.JsonSlurper

	properties(
		[
			parameters([
				string(defaultValue: '', name: 'targetEnvironmentName'),
				string(defaultValue: '1.30', name: 'targetEksVersion'),
			])
		]
	)

	String targetEnvironmentName = params.targetEnvironmentName
	String targetEksVersion = params.targetEksVersion
	String envDirectory
	String environmentDir
	String awsAccountId
	String awsRegion
	String timeStamp=currentBuild.startTimeInMillis 
	String tempDir="temp$BUILD_NUMBER"
	String eksAddonsTheorycraftDirectory = "live/175114933870/theorycraft/justice/us-east-2/prod/eks_addon"
	String storageClassYamlPath = "manifests/clusters/abcdexample/justice/us-east-2/dev2/sync/core/storageclass.yaml"
	String fluxPvcYaml = "manifests/clusters/abcdexample/justice/us-east-2/dev2/sync/core/flux-volume.yaml"
	String eksAddonFolderName
	String ebsCSIDriverSA
	Boolean eksAddonFolderExists
	Boolean isEbsCsiDriverMigrated
	Boolean isVpcCniMigrated
	Boolean isCoreDnsMigrated
	Boolean isKubeProxyMigrated
	String gameTag
	String vpcCniVer
	String ebsCsiVer
	String coreDnsVer
	String kubeProxyVer
	String BB_BRANCH_NAME
	currentBuild.displayName = "#${BUILD_NUMBER}-${targetEnvironmentName}-migrate-addons"

	node('hosting-agent') {
		container('tool') {
			dir(tempDir){
				stage('Clone iac repository') {
					sshagent(['bitbucket-repo-read-only']) {
						sh """#!/bin/bash
							set -e
							export GIT_SSH_COMMAND="ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no"
							git clone --quiet "git@bitbucket.org:accelbyte/iac.git" || true
							rm -rf iacTemp || true
							cp -R iac iacTemp || true
							chmod -R 777 iacTemp || true
							rm -rf ~/.aws/config || true
						"""
					}
				}
				
				stage('Set aws credentials'){
					def (customer, project, environment) = targetEnvironmentName.split('-')
					dir('iacTemp') {
						envDirectory = sh(returnStdout: true, script: """
							clusterDir=\$(find live -path "*/${customer}/${project}/*" -type d -name "eks_irsa" | grep ${environment} | grep -v terragrunt-cache | head -n 1)
							dirname \${clusterDir}
						""").trim()
						awsAccountId = sh(returnStdout: true, script: """
							echo ${envDirectory} | egrep -o '[[:digit:]]{12}'
						""").trim()
						awsRegion = sh(returnStdout: true, script: """
							basename \$(dirname ${envDirectory})
						""").trim()
						mainfestClusterDirectory = sh(returnStdout: true, script: """
							find manifests -path "*/${customer}/${project}/${awsRegion}/${environment}"
						""").trim()
						syncClusterDirectory = "${mainfestClusterDirectory}/sync"
		
						awsAccessMerged = sh(returnStdout: true, script: """
							set +x
							export \$(printf "AWS_ACCESS_KEY_ID=%s AWS_SECRET_ACCESS_KEY=%s AWS_SESSION_TOKEN=%s" \\
							\$(aws sts assume-role \\
							--role-arn arn:aws:iam::${awsAccountId}:role/${targetEnvironmentName}-automation-platform \\
							--role-session-name ${targetEnvironmentName} \\
							--query "Credentials.[AccessKeyId,SecretAccessKey,SessionToken]" \\
							--output text))

							export \$(printf "AWS_ACCESS_KEY_ID=%s AWS_SECRET_ACCESS_KEY=%s AWS_SESSION_TOKEN=%s" \\
							\$(aws sts assume-role \\
							--role-arn arn:aws:iam::${awsAccountId}:role/${targetEnvironmentName}-automation-platform-terraform \\
							--role-session-name ${targetEnvironmentName} \\
							--query "Credentials.[AccessKeyId,SecretAccessKey,SessionToken]" \\
							--output text))
							echo \${AWS_ACCESS_KEY_ID}:\${AWS_SECRET_ACCESS_KEY}:\${AWS_SESSION_TOKEN}
						""").trim()

						def (awsAccessKeyId, awsSecretAcceessKey, awsSessionToken) = awsAccessMerged.split(':')
						env.AWS_ACCESS_KEY_ID = awsAccessKeyId
						env.AWS_SECRET_ACCESS_KEY = awsSecretAcceessKey
						env.AWS_SESSION_TOKEN = awsSessionToken
						env.AWS_DEFAULT_REGION = awsRegion
						env.AWS_REGION = awsRegion
						sh 'aws sts get-caller-identity --no-cli-pager'
					}
				}

				stage('Generate Kubeconfig') {
					sh """#!/bin/bash
						set -e
						set -o pipefail
						envsubst < ~/.aws/config.template > ~/.aws/config
						aws eks update-kubeconfig --name ${targetEnvironmentName} --region ${awsRegion}

						wget -qO- https://github.com/fluxcd/flux2/releases/download/v0.37.0/flux_0.37.0_linux_amd64.tar.gz | tar -xz
						chmod +x flux
						mv flux /bin/flux

						wget https://github.com/mikefarah/yq/releases/download/v4.44.3/yq_linux_amd64 -O /bin/yq
						chmod +x /bin/yq
						yq --version

						which flux
						flux version --client
					"""
				}

				stage('Prepare common variable') {
					dir("iacTemp") {
						dir("${envDirectory}") {
							if(fileExists("eks_addon")) {
								eksAddonFolderName = "eks_addon"
								eksAddonFolderExists = true
							} else if (fileExists("eks_addons")) {
								eksAddonFolderName = "eks_addons"
								eksAddonFolderExists = true
							} else {
								eksAddonFolderName = "eks_addon"
								eksAddonFolderExists = false
								echo "EKS Addon Folder not found"
							}

							echo "eksAddonFolderName = ${eksAddonFolderName}"

							dir("eks") {
								gameTag = sh( returnStdout: true, script: """
									hcledit -f terragrunt.hcl attribute get "locals.game"
								""").trim()

								echo "gameTag = ${gameTag}"
							}

							notEligibleWorkerNodes = sh( returnStdout: true, script: """
								kubectl get no -oyaml | yq '.items[] | select(.status.nodeInfo.kubeletVersion | contains("${targetEksVersion}") | not) | .metadata.name'
							""").trim()

							countNotEligibleWorkerNodes = sh( returnStdout: true, script: """
								kubectl get no -o yaml | yq '[.items[] | select(.status.nodeInfo.kubeletVersion | contains("${targetEksVersion}") | not) | .metadata.name] | length'
							""").trim().toInteger()

							echo "countNotEligibleWorkerNodes: ${countNotEligibleWorkerNodes}"

							if (countNotEligibleWorkerNodes > 0) {
								def notEligibleWorkerNode = notEligibleWorkerNodes.split("\n")
								for (workerNode in notEligibleWorkerNode) {
									echo "workerNode ${workerNode} is not ${targetEksVersion}, please cordon and drain the node"
								}
								error "Build failed due found uneligible node"
							} else {
								echo "All nodes is eligible EKS worker node (${targetEksVersion}). Continue.."
							}
						}

						dir("scripts/eks-helper") {
							vpcCniVer = sh( returnStdout: true, script: """
								yq eval ".KUBERNETES_VERSION.${targetEksVersion}.ADDONS.VPC_CNI_VERSION" eks.yaml
							""").trim()
							ebsCsiVer = sh( returnStdout: true, script: """
								yq eval ".KUBERNETES_VERSION.${targetEksVersion}.ADDONS.EBS_CSI_VERSION" eks.yaml
							""").trim()
							coreDnsVer = sh( returnStdout: true, script: """
								yq eval ".KUBERNETES_VERSION.${targetEksVersion}.ADDONS.COREDNS_VERSION" eks.yaml
							""").trim()
							kubeProxyVer = sh( returnStdout: true, script: """
								yq eval ".KUBERNETES_VERSION.${targetEksVersion}.ADDONS.KUBE_PROXY_VERSION" eks.yaml
							""").trim()

							if("" || !coreDnsVer || !ebsCsiVer || !kubeProxyVer || !vpcCniVer){
								error "Addons versions are not set"
							} else {
								echo "VPC-CNI version is ${vpcCniVer}"
								echo "EBS_CSI_VERSION version is ${ebsCsiVer}"
								echo "COREDNS_VERSION version is ${coreDnsVer}"
								echo "KUBE_PROXY_VERSION version is ${kubeProxyVer}"
							}
						}
					}
				}

				stage('Pre-check') {
					dir("iac") {
						dir("${envDirectory}") {
							if(fileExists("${eksAddonFolderName}")) {
								dir("${eksAddonFolderName}") {
									def eksHcl = readFile("terragrunt.hcl")

									isEbsSaExists = sh( returnStdout: true, script: """
										kubectl -n kube-system get sa ebs-csi-controller-sa -oyaml | yq .metadata.name
									""").trim()
									
									if (eksHcl.contains("ebs-csi-driver") && isEbsSaExists.contains("ebs-csi-controller-sa")) {
										echo "ebs-csi-driver is on eks_addons. Continue upgrading"
										isEbsCsiDriverMigrated = true
									} else {
										echo "ebs-csi-driver is not on eks_addons folder. Continue Migrating"
										isEbsCsiDriverMigrated = false
									}

									if (eksHcl.contains("vpc-cni")) {
										echo "vpc-cni is on eks_addons. Continue upgrading"
										isVpcCniMigrated = true
									} else {
										echo "vpc-cni is not on eks_addons folder. Continue Migrating"
										isVpcCniMigrated = false
									}

									if (eksHcl.contains("kube-proxy")) {
										echo "kube-proxy is on eks_addons. Continue upgrading"
										isKubeProxyMigrated = true
									} else {
										echo "kube-proxy is not on eks_addons folder. Continue Migrating"
										isKubeProxyMigrated = false
									}

									if (eksHcl.contains("coredns")) {
										echo "coredns is on eks_addons. Continue upgrading"
										isCoreDnsMigrated = true
									} else {
										echo "coredns is not on eks_addons folder. Continue Migrating"
										isCoreDnsMigrated = false
									}

									echo "Suspend kustomization flux-system"
									sh """
										flux -n flux-system suspend ks flux-system
									"""
								}
							} else {
								echo "VPC CNI and EBS CSI not migrated yet"
								isEbsCsiDriverMigrated = false
								isVpcCniMigrated = false
								isKubeProxyMigrated = false
								isCoreDnsMigrated = false
								
								echo "INFO: Suspend kustomization flux-system"
								sh """
									flux -n flux-system suspend ks flux-system
								"""
							}

							dir("sso_roles") {
								sh """
									terragrunt apply --auto-approve
								"""
							}
						}
					}
				}

				stage('Update Manifest Cluster Folder') {
					dir("iac") {
						if(!fileExists("${syncClusterDirectory}/core/storageclass.yaml")) {
							sh "cp ${storageClassYamlPath} ${syncClusterDirectory}/core/storageclass.yaml"
							sh "cat ${storageClassYamlPath} ${syncClusterDirectory}/core/storageclass.yaml"
						}
						if(!fileExists("${syncClusterDirectory}/core/flux-volume.yaml")) {
							sh "cp ${fluxPvcYaml} ${syncClusterDirectory}/core/flux-volume.yaml"
							sh "cat ${fluxPvcYaml} ${syncClusterDirectory}/core/flux-volume.yaml"
						}
						esoVersion = sh( returnStdout: true, script: """
							yq .spec.path ${syncClusterDirectory}/core/external-secrets-operator.yaml | grep "external-secrets-operator"  | cut -d'/' -f5
						""").trim()
						esoPath = sh( returnStdout: true, script: """
							yq .spec.path ${syncClusterDirectory}/core/external-secrets-operator.yaml
						""").trim()
						if (esoVersion != "v0.8.7-1") {
							newEsoPath = esoPath.replace("${esoVersion}","v0.8.7-1")
							sh """
								yq -i 'select(.metadata.name == "external-secrets-operator") | .spec.path = "${newEsoPath}"' ${syncClusterDirectory}/core/external-secrets-operator.yaml
							"""
						}

						sh "yq .spec.path ${syncClusterDirectory}/core/external-secrets-operator.yaml"

						fluxVersion = sh( returnStdout: true, script: """
							yq .spec.path ${syncClusterDirectory}/core/flux.yaml | cut -d'/' -f5
						""").trim()
						fluxPath = sh( returnStdout: true, script: """
							yq .spec.path ${syncClusterDirectory}/core/flux.yaml
						""").trim()
						if (fluxVersion != "v0.37.0-4") {
							newFluxPath = fluxPath.replace("${fluxVersion}","v0.37.0-4")
							sh """
								yq -i 'select(.metadata.name == "flux") |.spec.path = "${newFluxPath}"' ${syncClusterDirectory}/core/flux.yaml
							"""
						}

						sh "yq .spec.path ${syncClusterDirectory}/core/flux.yaml"

						if(fileExists("${syncClusterDirectory}/core/vpc-cni.yaml")) {
							sh """
								rm ${syncClusterDirectory}/core/vpc-cni.yaml
								yq e '(.bases[] |= select(. == "./vpc-cni.yaml") = "./flux-volume.yaml")' -i  ${syncClusterDirectory}/core/kustomization.yaml
							"""
						}

						if(fileExists("${syncClusterDirectory}/core/ebs-csi-driver.yaml")) {
							sh """
								rm ${syncClusterDirectory}/core/ebs-csi-driver.yaml
								yq e '(.bases[] |= select(. == "./ebs-csi-driver.yaml") = "./storageclass.yaml")' -i ${syncClusterDirectory}/core/kustomization.yaml
							"""
						}

						echo "Patch manifest cluster directory"
						dir("${syncClusterDirectory}") {
							dependsOnVpcCniPath = sh( returnStdout: true, script: """
								grep -rl "name: vpc-cni" . || echo "NotFound"
							""").trim().split("\n")

							echo "dependsOnVPCCNIpath = ${dependsOnVpcCniPath}"

							if (!dependsOnVpcCniPath.contains("NotFound")) {
								for (path in dependsOnVpcCniPath) {
									sh """
										yq -i 'del(.spec.dependsOn[] | select(.name == "vpc-cni"))' ${path}
									"""
								}

								sh """
									grep -r "vpc-cni" . || echo "NotFound"
								"""
							}

							dependsOnEbsCsiPath = sh( returnStdout: true, script: """
								grep -rl "name: ebs-csi-driver" . || echo "NotFound"
							""").trim()

							echo "dependsOnEbsCsiPath = ${dependsOnEbsCsiPath}"

							if (!dependsOnEbsCsiPath.contains("NotFound")) {
								dependsOnEbsCsiPath = dependsOnEbsCsiPath.split("\n")
								for (path in dependsOnEbsCsiPath) {
									sh """
										yq -i 'del(.spec.dependsOn[] | select(.name == "ebs-csi-driver"))' ${path}
									"""
								}

								sh """
									grep -r "ebs-csi-driver" . || echo "NotFound"
								"""
							}
						}
					}
				}

				stage('Move EKS addon to eks_addon folder') {
					echo "eksAddonFolderExists = ${eksAddonFolderExists}"
					dir("iacTemp") {
						dir("${envDirectory}") {
							if (eksAddonFolderExists == false) {
								echo "Remove addon_list and terragrunt state from eks/terragrunt.hcl"
								dir("eks"){
									sh """
										terragrunt init
									"""
									def eksHcl = readFile("terragrunt.hcl")

									if (eksHcl.contains("kube-proxy")) {
										echo "INFO: start removing terragrunt state kube-proxy"
										sh """
											set +x
											terragrunt state rm 'aws_eks_addon.eks_addon_list["kube-proxy"]' || true
										"""
										echo "INFO: terragrunt state kube-proxy is removed"
									}

									if (eksHcl.contains("coredns")) {
										echo "INFO: start removing terragrunt state coredns"
										sh """
											set +x
											terragrunt state rm 'aws_eks_addon.eks_addon_list["coredns"]' || true
										"""
										echo "INFO: terragrunt state coredns is removed"
									}

									if (eksHcl.contains("ebs-csi-driver")) {
										echo "INFO: start removing terragrunt state ebs-csi-driver"
										sh """
											set +x
											terragrunt state rm 'aws_eks_addon.eks_addon_list["ebs-csi-driver"]' || true
										"""
										echo "INFO: terragrunt state ebs-csi-driver is removed"
									}

									if (eksHcl.contains("vpc-cni")) {
										echo "INFO: start removing terragrunt state vpc-cni"
										sh """
											set +x
											terragrunt state rm 'aws_eks_addon.eks_addon_list["vpc-cni"]' || true
										"""
										echo "INFO: terragrunt state vpc-cni is removed"
									}

									if (eksHcl.contains("addon_list")) {
										sh """
											set +x
											sed -i "s/inputs = {/inputs {/g" terragrunt.hcl

											hcledit -f terragrunt.hcl attribute rm "inputs.addon_list" -u || true

											sed -i "s/inputs {/inputs = {/g" terragrunt.hcl

											cat terragrunt.hcl
										"""
									}
								}
							}
						}

						if (eksAddonFolderExists == false) {
							sh """
								set +x
								echo "Copying eks_addon/terragrunt.hcl to ${envDirectory}"
								cp -r ${eksAddonsTheorycraftDirectory} ${envDirectory}/
								sed -i "s|loki|${gameTag}|g" ${envDirectory}/${eksAddonFolderName}/terragrunt.hcl || true
								cat ${envDirectory}/${eksAddonFolderName}/terragrunt.hcl
							"""
						}
					}
				}

				stage('Update Addons VPC-CNI') {
					dir("iacTemp") {
						dir("${envDirectory}") {
							dir("${eksAddonFolderName}") {
								vpcCNILineNumber = sh( returnStdout: true, script: """
									set +x
									grep -n "vpc-cni" terragrunt.hcl | tail -n 1 | cut -d':' -f1
								""").trim()
								vpcCniLineNumberInt = Integer.parseInt(vpcCNILineNumber) + 1

								echo "vpcCNILineNumber = ${vpcCniLineNumberInt}"

								sh """
									grep -n "vpc-cni" terragrunt.hcl -A 1

									sed -i '${vpcCniLineNumberInt}s|addon_version[[:space:]]*=[[:space:]].*|addon_version = \"${vpcCniVer}\"|' terragrunt.hcl

									hcledit -f terragrunt.hcl fmt -u

									grep -n "vpc-cni" terragrunt.hcl -A 1
								"""
							}
						}	
					}
				}

				stage('Update Addons EBS CSI Driver') {
					dir("iacTemp") {
						dir("${envDirectory}") {
							dir("eks_irsa") {
								echo "Applying EKS IRSA for EBS CSI Driver"
								def eksIrsaHcl = readFile("terragrunt.hcl")

								if(eksIrsaHcl.contains("ebs-csi-controller-sa")) {
									echo "Skip Adding EBS CSI SA"
								} else {
									sh """
										set +x
										
										sed -i "s/inputs = {/inputs {/g" terragrunt.hcl

										hcledit -f terragrunt.hcl attribute append "inputs.ebs_csi_driver_serviceaccount" "ebs-csi-controller-sa" -u

										sed -i "s/inputs {/inputs = {/g" terragrunt.hcl

										sed -i 's|ebs-csi-controller-sa|\"ebs-csi-controller-sa\"|g' terragrunt.hcl

										terragrunt hclfmt terragrunt.hcl

										cat terragrunt.hcl
									"""
								}
							}
						}
						
						dir("${envDirectory}") {
							dir("${eksAddonFolderName}") {
								ebsCSIDriverLineNumber = sh( returnStdout: true, script: """
									set +x
									grep -n "aws-ebs-csi-driver" terragrunt.hcl | tail -n 1 | cut -d':' -f1
								""").trim()
								ebsCsiLineNumberInt = Integer.parseInt(ebsCSIDriverLineNumber) + 1

								echo "ebsCSIDriverLineNumber = ${ebsCsiLineNumberInt}"

								sh """
									set +x
									sed -i "s|loki|${gameTag}|g" terragrunt.hcl || true

									grep -n "aws-ebs-csi-driver" terragrunt.hcl -A 1
										
									sed -i '${ebsCsiLineNumberInt}s|addon_version[[:space:]]*=[[:space:]].*|addon_version = \"${ebsCsiVer}\"|' terragrunt.hcl

									hcledit -f terragrunt.hcl fmt -u

									grep -n "aws-ebs-csi-driver" terragrunt.hcl -A 1
								"""
							}
						}
					}
				}

				stage('Update EKS Addon kube-proxy') {
					dir("iacTemp") {
						dir("${envDirectory}") {
							dir("${eksAddonFolderName}") {
								kubeProxyLineNumber = sh( returnStdout: true, script: """
									set +x
									grep -n "kube-proxy" terragrunt.hcl | tail -n 1 | cut -d':' -f1
								""").trim()
								kubeProxyLineNumberInt = Integer.parseInt(kubeProxyLineNumber) + 1

								echo "kubeProxyLineNumber = ${kubeProxyLineNumberInt}"

								sh """
									set +x
									sed -i "s|loki|${gameTag}|g" terragrunt.hcl || true

									grep -n "kube-proxy" terragrunt.hcl -A 1
										
									sed -i '${kubeProxyLineNumberInt}s|addon_version[[:space:]]*=[[:space:]].*|addon_version = \"${kubeProxyVer}\"|' terragrunt.hcl

									hcledit -f terragrunt.hcl fmt -u

									grep -n "kube-proxy" terragrunt.hcl -A 1
								"""
							}
						}
					}
				}

				stage('Update EKS Addon CoreDNS') {
					dir("iacTemp") {
						dir("${envDirectory}") {
							dir("${eksAddonFolderName}") {
								coreDnsLineNumber = sh( returnStdout: true, script: """
									set +x
									grep -n "coredns" terragrunt.hcl | tail -n 1 | cut -d':' -f1
								""").trim()
								coreDNSLineNumberInt = Integer.parseInt(coreDnsLineNumber) + 1

								echo "coreDnsLineNumber = ${coreDNSLineNumberInt}"

								sh """
									set +x
									sed -i "s|loki|${gameTag}|g" terragrunt.hcl || true

									grep -n "coredns" terragrunt.hcl -A 1
										
									sed -i '${coreDNSLineNumberInt}s|addon_version[[:space:]]*=[[:space:]].*|addon_version = \"${coreDnsVer}\"|' terragrunt.hcl

									hcledit -f terragrunt.hcl fmt -u

									grep -n "coredns" terragrunt.hcl -A 1
								"""
							}
						}
					}
				}

				stage('Commit and Push IAC Repo'){
					BB_BRANCH_NAME="jenkins-${awsAccountId}-${targetEnvironmentName}-migrate-addons-${timeStamp}"
					sshagent(['bitbucket-repo-read-only']) {
						sh """#!/bin/bash
							set -e
							export GIT_SSH_COMMAND="ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no"

							rm -rf iac/${envDirectory}/${eksAddonFolderName} || true
							rm -rf iac/${envDirectory}/eks || true
							rm -rf iac/${envDirectory}/eks_irsa || true
							cp -R iacTemp/${envDirectory}/${eksAddonFolderName} iac/${envDirectory}/
							cp -R iacTemp/${envDirectory}/eks/ iac/${envDirectory}/
							cp -R iacTemp/${envDirectory}/eks_irsa/ iac/${envDirectory}/
							cd iac
							chmod -R 644 ${envDirectory}/${eksAddonFolderName} || true
							chmod -R 644 ${envDirectory}/eks || true
							chmod -R 644 ${envDirectory}/eks_irsa || true
							git checkout -b ${BB_BRANCH_NAME}
							git config --global user.email "build@accelbyte.net"
							git config --global user.name "Build AccelByte"
							git status
							git add ${envDirectory}/${eksAddonFolderName} || true
							git add ${envDirectory}/eks || true
							git add ${envDirectory}/eks_irsa || true
							git add ${mainfestClusterDirectory} || true
							git commit -m "feat: ${BB_BRANCH_NAME}"
							git push --set-upstream origin ${BB_BRANCH_NAME}
						"""
					}
				}

				stage("Create PR IAC Repo") {
					prSummary="""
	:: Migrate Addons ${targetEnvironmentName} \n \n
					"""
					withCredentials([string(credentialsId: "BuildAccountBitbucketAuthBasicB64", variable: 'BuildAccountBitbucketAuthBasicB64')]) {
						def post = new URL("https://bitbucket.org/api/2.0/repositories/accelbyte/iac/pullrequests").openConnection();
						def postData =  [
							title: "feat: migrate addons ${targetEnvironmentName}",
							source: [
								branch: [
									name: "${BB_BRANCH_NAME}"
								]
							],
							reviewers: [
									[
											uuid: "{ede31de1-392c-42cc-bf10-f5e45d112a0f}" // Mochammad Rifky Satria Perdana
									],
									[
											uuid: "{6cee0dcd-3d6e-4ef1-8cd0-4ca217ed32d2}" // Adin Baskoro Pratomo
									],
									[
											uuid: "{f115f536-48bf-42f0-9634-30f53f03ed13}" // Adi Purnomo
									],
									[
											uuid: "{8f4086ef-41e9-4eb3-80c0-84212c1c7594}" // Fahmi Maghrizal Mochtar
									],
									[
											uuid: "{e7b16827-0453-4ab8-b70e-2ec781beb07f}" // Rasyiq Farandi
									],
									[
											uuid: "{3bc5a80a-bb63-40a5-829c-82cbde04c2a3}" // Radian Satria Panigas
									],
									[
											uuid: "{b0089c2d-a507-4211-bc6f-e23cd1735f7a}" // Muhamad Ar Ghifary
									],
									[
											uuid: "{4c57253e-0370-446c-8824-ee350e24b4df}" // Robbie Zhang
									],
									[
											uuid: "{a60f808f-4034-49da-89f3-4daf9a2367b6}" // Husni Bakri
									],
									[
											uuid: "{6fcceadc-6463-4a17-9af0-c942f2f43e06}" // Aditya Novian Firdaus
									]
							],
							destination: [
								branch: [
									name: "master"
								]
							],
							summary: [
								raw: "${prSummary}"
							],
							close_source_branch: true
						]
						def jsonPayload = JsonOutput.toJson(postData)
						post.setRequestMethod("POST")
						post.setDoOutput(true)
						post.setRequestProperty("Content-Type", "application/json")
						post.setRequestProperty("Authorization", "Basic ${BuildAccountBitbucketAuthBasicB64}")
						post.getOutputStream().write(jsonPayload.getBytes("UTF-8"));
						def postRC = post.getResponseCode();
						println(postRC);
						if(postRC.equals(200) || postRC.equals(201)) {
							def jsonSlurper = new JsonSlurper()
							def reply = post.getInputStream().getText()
							def replyMap = jsonSlurper.parseText(reply)
							prHtmlLink = replyMap.links.html.href
							println(replyMap);
						}
					}
				}

				stage('Applying Addons VPC CNI') {
					dir("iacTemp") {
						dir("${envDirectory}") {
							dir("${eksAddonFolderName}") {
								sh """
									terragrunt init
									terragrunt plan --target='aws_eks_addon.eks_addon_list["vpc-cni"]' -out=addon_vpc_cni
								"""
								def userInput = input(
									id: 'userInput', 
									message: 'Are you sure to migrate VPC CNI? Please run "kubectl -n kube-system | grep Pending" and ensure no pending DS', 
									parameters: [
										[$class: 'BooleanParameterDefinition', defaultValue: false, description: '', name: 'Are you sure to proceed']
									]
								)

								if(!userInput) {
									echo "Build Manually"
								}
								sh """
									kubectl patch -n flux-system kustomizations.kustomize.toolkit.fluxcd.io vpc-cni -p '{"spec":{"prune":false}}' --type=merge || true
									kubectl -n flux-system delete kustomizations.kustomize.toolkit.fluxcd.io vpc-cni || true

									echo "Applying VPC CNI"
									terragrunt apply "addon_vpc_cni"
								"""
							}
						}
					}
				}

				stage('Applying Addons EBS CSI Driver') {
					dir("iacTemp") {
						dir("${envDirectory}") {
							dir("eks_irsa") {
								sh """
									terragrunt plan --target=aws_iam_role.ebs_csi_driver -out=sa_ebs_csi
								"""
							
								def userInput = input(
									id: 'userInput', 
									message: 'Are you sure to apply EBS CSI Driver SA? Please run "kubectl -n kube-system | grep Pending" and ensure no pending DS', 
									parameters: [
										[$class: 'BooleanParameterDefinition', defaultValue: false, description: '', name: 'Are you sure to proceed']
									]
								)

								if(!userInput) {
									echo "Build Manually"
								}

								sh """
									terragrunt apply "sa_ebs_csi"
								"""
							}

							sleep(10)

							ebsCSIDriverSA = sh( returnStdout: true, script: """
								set +x
								kubectl -n kube-system get sa ebs-csi-controller-sa -oyaml | yq .metadata.name || echo "NotFound"
							""").trim()

							if(ebsCSIDriverSA.contains("NotFound")) {
								echo "WARNING: SA for EBS CSI Not exists. Please apply manually"
							}

							dir("${eksAddonFolderName}") {
								sh """
									terragrunt plan --target='aws_eks_addon.eks_addon_list["ebs-csi-driver"]' -out=addon_ebs_csi
								"""

								def userInput = input(
									id: 'userInput', 
									message: 'Are you sure to migrate EBS CSI Driver? Please ensure sa already created by running "kubectl -n kube-system get sa ebs-csi-controller-sa', 
									parameters: [
										[$class: 'BooleanParameterDefinition', defaultValue: false, description: '', name: 'Are you sure to proceed']
									]
								)

								if(!userInput) {
									echo "Build Manually"
								}

								sh """
									kubectl patch -n flux-system kustomizations.kustomize.toolkit.fluxcd.io ebs-csi-driver -p '{"spec":{"prune":true}}' --type=merge || true
									kubectl -n flux-system delete kustomizations.kustomize.toolkit.fluxcd.io ebs-csi-driver || true

									terragrunt apply "addon_ebs_csi"
								"""
							}
						}
					}
				}

				stage('Applying Addons Kube-Proxy') {
					dir("iacTemp") {
						dir("${envDirectory}") {
							dir("${eksAddonFolderName}") {
								sh """
									terragrunt import 'aws_eks_addon.eks_addon_list["kube-proxy"]' '${targetEnvironmentName}:kube-proxy' || true

									sleep 5

									terragrunt plan --target='aws_eks_addon.eks_addon_list["kube-proxy"]' -out=addon_kube_proxy
								"""

								def userInput = input(
									id: 'userInput', 
									message: 'Are you sure to apply kube-proxy?', 
									parameters: [
										[$class: 'BooleanParameterDefinition', defaultValue: false, description: '', name: 'Are you sure to proceed']
									]
								)

								if(!userInput) {
									echo "Build Manually"
								}
								sh """
									terragrunt apply "addon_kube_proxy"
								"""
							}
						}
					}
				}

				stage('Applying Addons CoreDNS') {
					dir("iacTemp") {
						dir("${envDirectory}") {
							dir("${eksAddonFolderName}") {
								sh """
									terragrunt import 'aws_eks_addon.eks_addon_list["coredns"]' '${targetEnvironmentName}:coredns' || true

									sleep 5
									
									terragrunt plan --target='aws_eks_addon.eks_addon_list["coredns"]' -out=addon_coredns
								"""

								def userInput = input(
									id: 'userInput', 
									message: 'Are you sure to apply coredns?', 
									parameters: [
										[$class: 'BooleanParameterDefinition', defaultValue: false, description: '', name: 'Are you sure to proceed']
									]
								)

								if(!userInput) {
									echo "Build Manually"
								}
								sh """
									terragrunt apply "addon_coredns"
								"""
							}
						}
					}
				}

				stage("Resume kustomization flux-system") {
					def userInput = input(
						id: 'userInput', 
						message: 'Before resuming, please merge the PR first. Once merged please continue ...', 
						parameters: [
							[$class: 'BooleanParameterDefinition', defaultValue: false, description: '', name: 'Are you sure to proceed']
						]
					)

					if(!userInput) {
						echo "Build Manually"
					}

					sh """
						set +x
						kubectl -n flux-system delete kustomizations.kustomize.toolkit.fluxcd.io ebs-csi-driver || true
						kubectl -n flux-system delete kustomizations.kustomize.toolkit.fluxcd.io vpc-cni || true

						flux reconcile source git flux-system
						flux -n flux-system resume kustomization flux-system

						kubectl -n flux-system delete kustomizations.kustomize.toolkit.fluxcd.io ebs-csi-driver || true
						kubectl -n flux-system delete kustomizations.kustomize.toolkit.fluxcd.io vpc-cni || true
					"""
				}
			}
		}
	}