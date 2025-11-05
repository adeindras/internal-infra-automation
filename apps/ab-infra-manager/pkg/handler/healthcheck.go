package handler

import (
	"accelbyte/ab-infra-manager/pkg/models"
	"net/http"

	"github.com/gin-gonic/gin"
)

type HealthCheck struct {
	Status string
	Config *models.Cfg
}

func (hc *HealthCheck) HealthCheckHandler(c *gin.Context) {
	// TODO: Healthcheck algorithm
	hc.Status = "healthy"
	c.JSON(http.StatusOK, gin.H{
		"status": hc.Status,
		"config": hc.Config,
	})
}
