package controller

import (
	"accelbyte/ab-infra-manager/pkg/k8s"
	"accelbyte/ab-infra-manager/pkg/utils"
	"fmt"
	"log/slog"
	"time"

	"github.com/prometheus/client_golang/prometheus"
)

func (a *App) TickerStart(done <-chan bool) {
	ticker := time.NewTicker(120 * time.Second)
	go func() {
		for ; true; <-ticker.C {
			select {
			case <-done:
				ticker.Stop()
				return
			default:
				a.appStateUpdate()
				for ks, metric := range a.metrics {
					ksPath, _ := k8s.GetKustomizationPath(a.config.K8sDynamicClientSet, a.config.KsNamespace, ks)
					version, err := utils.ParseVersion(ksPath)
					if err != nil {
						slog.Error("error parsing version from path", slog.Any("Path", ksPath))
					}
					subversion := utils.ParseSubVersion(ksPath)
					metric.CCU.Reset()
					metric.CCU.With(prometheus.Labels{
						"environment":    a.state.Environment,
						"aws_account_id": a.state.AwsAccountId,
						"aws_region":     a.state.AwsRegion,
						"resource":       ks,
						"version":        version,
						"patch":          subversion,
						"live":           fmt.Sprint(a.state.Live),
					}).Set(float64(a.config.CCU))
				}
			}
		}
	}()
}

func (a *App) TickerStop(done chan<- bool) {
	done <- true
}
