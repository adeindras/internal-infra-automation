package models

type AppState struct {
	Environment  string
	AwsAccountId string
	AwsRegion    string
	Live         bool
}
