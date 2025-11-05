package args

import "github.com/jxskiss/mcli"

type Args struct {
	Ref       string `cli:"-r, --ref, please specify the git ref" default:"master"`
	Workspace string `cli:"-w, --workspace, please specify the workspace" default:"accelbyte"`
	RepoSlug  string `cli:"-s, --repo-slug, please specify the repository slug" default:"deployments"`
	Dirs      string `cli:"-d, --dirs, specify the directories that you want to download, comma separated"`
	Files     string `cli:"-f, --files, specify the files that you want to download, comma separated"`
	Output    string `cli:"-o, --output, The output directory" default:"/tmp/bitbucket-downloader-output/"`
	// This argument reads environment variable and requires the variable to exist,
	// it doesn't accept input from command line.
	BitbucketAccessToken string `cli:"#ER, The bitbucket access token" env:"BITBUCKET_ACCESS_TOKEN"`
}

func (a *Args) Parse() {
	mcli.Parse(a)
}
