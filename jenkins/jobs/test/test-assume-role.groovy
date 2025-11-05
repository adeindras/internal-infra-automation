properties(
    [
        parameters([
            choice(choices: ["sandbox-justice-dev"], description: "", name: "targetEnvironmentName")
        ])
    ]
)

podTemplate(yaml: """
    apiVersion: v1
    kind: Pod
    metadata:
      annotations:
        karpenter.sh/do-not-disrupt: "true"
    spec:
      serviceAccountName: jenkins-agent-${targetEnvironmentName}
      containers:
        - name: tool
          image: 268237257165.dkr.ecr.us-east-2.amazonaws.com/jenkinscd-agent:v1.0.0-infraapps-v4
          command:
            - cat
          tty: true
          securityContext:
            runAsUser: 0
          resources:
            requests:
              memory: 500Mi
              cpu: 250m
      imagePullSecrets:
        - ecr-credentials
      nodeSelector:
        karpenter.sh/capacity-type: on-demand
        karpenter.sh/nodepool: default
    """) {
    node(POD_LABEL) {
      container('tool') {
        echo POD_CONTAINER // displays 'busybox'
        sh """
          aws sts get-caller-identity
        """
      }
    }
}

