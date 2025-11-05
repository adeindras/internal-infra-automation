package controller

import (
	"accelbyte/ab-infra-manager/pkg/k8s"
	"fmt"
	"log/slog"
)

func (a *App) appStateUpdate() {
	project := "justice"
	clusterVariablesCmData, err := k8s.GetConfigmap(a.config.K8sClientSet, project, "cluster-variables")
	if err != nil {
		slog.Error("error getting cluster-variables configmap")
	}

	// from configmap
	customer := clusterVariablesCmData["CUSTOMER_NAME"]
	envName := clusterVariablesCmData["ENVIRONMENT_NAME"]
	a.state.AwsAccountId = clusterVariablesCmData["AWS_ACCOUNT_ID"]
	a.state.AwsRegion = clusterVariablesCmData["AWS_REGION"]
	a.state.Environment = fmt.Sprintf("%s-%s-%s", customer, project, envName)
	a.state.Live = a.config.Live
}
