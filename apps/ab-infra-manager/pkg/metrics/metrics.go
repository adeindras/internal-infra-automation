package metrics

import (
	"fmt"
	"strings"

	"github.com/prometheus/client_golang/prometheus"
)

type Metrics struct {
	CCU *prometheus.GaugeVec
}

func NewSingleMetrics(reg *prometheus.Registry, ks string) *Metrics {
	ksLabel := strings.ReplaceAll(ks, "-", "_")
	m := &Metrics{
		CCU: prometheus.NewGaugeVec(prometheus.GaugeOpts{
			Namespace: "ab_infra_manager",
			Name:      ksLabel,
			Help:      fmt.Sprintf("Infra CCU setup/tier based on AccelByte's MSA doc with %s version as labels", ksLabel),
		}, []string{"environment", "aws_account_id", "aws_region", "resource", "version", "patch", "live"}),
	}
	reg.MustRegister(m.CCU)
	return m
}

func Init(reg *prometheus.Registry, ksResources []string) map[string]*Metrics {
	m := make(map[string]*Metrics)
	for _, ks := range ksResources {
		met := NewSingleMetrics(reg, ks)
		m[ks] = met
	}
	return m
}
