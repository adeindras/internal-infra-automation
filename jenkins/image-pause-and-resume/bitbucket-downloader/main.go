package main

import (
	"accelbyte.io/bitbucket-downloader/pkg/args"
	"accelbyte.io/bitbucket-downloader/pkg/download"
)

func main() {
	args := &args.Args{}
	args.Parse()
	dl := download.New(args)
	dl.Execute()
}
