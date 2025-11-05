package handler

import (
	"accelbyte/ab-infra-manager/pkg/models"
	"net/http"

	"github.com/gin-gonic/gin"
)

type State struct {
	Data *models.AppState
}

func (s *State) GetAppStateHandler(c *gin.Context) {
	c.JSON(http.StatusOK, s.Data)
}
