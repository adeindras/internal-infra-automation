package controller

import (
	"accelbyte/ab-infra-manager/pkg/config"
	"accelbyte/ab-infra-manager/pkg/k8s"
	"accelbyte/ab-infra-manager/pkg/metrics"
	"accelbyte/ab-infra-manager/pkg/models"
	"context"
	"log/slog"
	"os"

	awsconfig "github.com/aws/aws-sdk-go-v2/config"
	"github.com/gin-gonic/gin"
	"github.com/prometheus/client_golang/prometheus"
)

type App struct {
	router  *gin.Engine
	config  *models.Cfg
	metrics map[string]*metrics.Metrics
	ch      *chan bool
	promReg *prometheus.Registry
	state   models.AppState
}

func (a *App) Init() {
	slog.Info("🤟 Initializing Apps")

	// configuration load
	c, err := config.Load()
	if err != nil {
		slog.Error(err.Error())
		os.Exit(1)
	}
	a.config = &c
	slog.SetLogLoggerLevel(slog.Level(c.LogLevel))

	cvars, err := k8s.GetConfigmap(a.config.K8sClientSet, "default", "cluster-variables")
	if err != nil {
		slog.Error("error getting cluster-variables configmap")
		os.Exit(1)
	}

	slog.Info("configure aws sdk")
	awsConfig, err := awsconfig.LoadDefaultConfig(context.Background(),
		awsconfig.WithRegion(cvars["AWS_REGION"]),
		awsconfig.WithSharedConfigProfile(a.config.AWSProfile),
	)
	if err != nil {
		slog.Error("unable to load SDK config", "error", err)
		os.Exit(1)
	}

	// prometheus & metrics stuff
	a.promReg = prometheus.NewRegistry()
	a.metrics = metrics.Init(a.promReg, a.config.KsResources)

	// channel/loop for updating data regularly
	ch := make(chan bool)
	a.ch = &ch
	a.TickerStart(*a.ch)

	a.promReg.MustRegister(
		&metrics.AWSSubnetCollector{
			Config:          awsConfig,
			CustomerName:    cvars["CUSTOMER_NAME"],
			EnvironmentName: cvars["ENVIRONMENT_NAME"],
			ProjectName:     cvars["PROJECT_NAME"],
		},
		metrics.NewAWSUsageCollector(
			*a.config, awsConfig,
			cvars["CUSTOMER_NAME"],
			cvars["ENVIRONMENT_NAME"],
			cvars["PROJECT_NAME"],
		),
	)

	// http gin setup
	gin.SetMode(gin.ReleaseMode)
	a.router = gin.Default()
	a.InitRoutes()
}

func (a *App) Run() {
	a.Init()
	slog.Info("🚀 Running server at 0.0.0.0:8080")
	// a.TickerStop(*a.ch)
	a.router.Run("0.0.0.0:8080")
}
