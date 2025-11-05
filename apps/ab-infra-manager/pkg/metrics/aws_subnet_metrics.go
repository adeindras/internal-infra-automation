package metrics

import (
	"context"
	"log/slog"
	"math"
	"net"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/ec2"
	"github.com/aws/aws-sdk-go-v2/service/ec2/types"
	"github.com/prometheus/client_golang/prometheus"
)

var (
	log = slog.Default()
)

type AWSSubnetCollector struct {
	Config          aws.Config
	CustomerName    string
	EnvironmentName string
	ProjectName     string
}

func (c *AWSSubnetCollector) Describe(ch chan<- *prometheus.Desc) {
	prometheus.DescribeByCollect(c, ch)
}

func (c *AWSSubnetCollector) Collect(ch chan<- prometheus.Metric) {
	ctx := context.Background()

	for _, metric := range c.scrape(ctx) {
		ch <- metric
	}
}

func (c *AWSSubnetCollector) scrape(ctx context.Context) []prometheus.Metric {
	var metrics []prometheus.Metric
	o, err := ec2.NewFromConfig(c.Config).DescribeSubnets(ctx, &ec2.DescribeSubnetsInput{
		Filters: []types.Filter{
			{
				Name:   aws.String("tag:customer_name"),
				Values: []string{c.CustomerName},
			},
			{
				Name:   aws.String("tag:environment_name"),
				Values: []string{c.EnvironmentName},
			},
			{
				Name:   aws.String("tag:project"),
				Values: []string{c.ProjectName},
			},
		},
	})
	if err != nil {
		log.Error("failed to scrape aws subnet", "error", err)
		return nil
	}

	for _, subnet := range o.Subnets {
		name := AWSTags(subnet.Tags).Name()
		subnetid := aws.ToString(subnet.SubnetId)
		cidr := aws.ToString(subnet.CidrBlock)
		vpcid := aws.ToString(subnet.VpcId)
		az := aws.ToString(subnet.AvailabilityZone)
		metrics = append(metrics, prometheus.MustNewConstMetric(
			prometheus.NewDesc(prometheus.BuildFQName("aws", "subnet", "available_ip"), "Available subnet IP", []string{"name", "cidr", "subnet_id", "vpc_id", "availability_zone"}, nil),
			prometheus.GaugeValue,
			float64(aws.ToInt32(subnet.AvailableIpAddressCount)),
			name, cidr, subnetid, vpcid, az))

		_, ipv4, _ := net.ParseCIDR(aws.ToString(subnet.CidrBlock))
		size, _ := ipv4.Mask.Size()
		maxHosts := float64(math.Pow(2, float64(32-size))) - 2
		metrics = append(metrics, prometheus.MustNewConstMetric(
			prometheus.NewDesc(prometheus.BuildFQName("aws", "subnet", "max_hosts"), "Maximum IP address of AWS Subnet", []string{"name", "cidr", "subnet_id", "vpc_id", "availability_zone"}, nil),
			prometheus.GaugeValue,
			maxHosts,
			name, cidr, subnetid, vpcid, az))
	}

	return metrics
}

type AWSTags []types.Tag

func (t AWSTags) Name() string {
	var name string
	for _, tag := range t {
		if aws.ToString(tag.Key) == "Name" {
			name = aws.ToString(tag.Value)
		}
		continue
	}
	return name
}
