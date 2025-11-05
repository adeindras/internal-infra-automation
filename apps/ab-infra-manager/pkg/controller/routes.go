package controller

import (
	"accelbyte/ab-infra-manager/pkg/handler"
)

func (a *App) InitRoutes() {
	a.router.GET("/healthchecker", (&handler.HealthCheck{Config: a.config}).HealthCheckHandler)
	a.router.GET("/metrics", handler.PrometheusHandler(a.promReg))

	v1 := a.router.Group("/v1")
	{
		v1.GET("/healthchecker", (&handler.HealthCheck{Config: a.config}).HealthCheckHandler)
		v1.GET("/metrics", handler.PrometheusHandler(a.promReg))
		v1.GET("/state", (&handler.State{Data: &a.state}).GetAppStateHandler)
	}
}
