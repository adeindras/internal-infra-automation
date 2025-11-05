package download

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httputil"
	"os"
	"path"
	"strings"
	"sync"

	"accelbyte.io/bitbucket-downloader/pkg/args"
	log "github.com/sirupsen/logrus"
	"golang.org/x/oauth2"
)

type DirectoryDownloadResponse struct {
	Values []struct {
		Path string `json:"path"`
		Type string `json:"type"`
	} `json:"values"`
	Next string `json:"next"`
}

type TargetDownload struct {
	oauth2.TokenSource
	*args.Args
	Dirs  []string
	Files []string
}

func New(c *args.Args) (td *TargetDownload) {
	td = &TargetDownload{}
	if len(c.Dirs) != 0 {
		td.Dirs = strings.Split(c.Dirs, ",")
	}
	if len(c.Files) != 0 {
		td.Files = strings.Split(c.Files, ",")
	}
	td.Args = c
	td.TokenSource = oauth2.StaticTokenSource(&oauth2.Token{
		AccessToken: c.BitbucketAccessToken,
	})
	return td
}

func (d *TargetDownload) WithToken(token oauth2.TokenSource) *TargetDownload {
	d.TokenSource = token
	return d
}

func (d *TargetDownload) Execute() {
	outputDir := d.Output
	if _, err := os.Stat(outputDir); os.IsNotExist(err) {
		os.MkdirAll(outputDir, 0700)
	}

	var wgdir sync.WaitGroup
	var mu sync.Mutex
	for _, dir := range d.Dirs {
		downloadUrl := BitbucketUrlBuilder(d.Workspace, d.RepoSlug, d.Ref, dir)
		wgdir.Add(1)
		go d.PopulateFileSlice(downloadUrl, &wgdir, &mu, -1)
	}
	wgdir.Wait()

	var wg sync.WaitGroup
	guard := make(chan error, 20)
	for _, file := range d.Files {
		wg.Add(1)
		fmt.Printf("Downloading: %s \n", file)
		guard <- nil
		go d.DownloadFile(file, &wg, guard)
	}
	wg.Wait()
	fmt.Println("All files are downloaded")
}

func (d *TargetDownload) PopulateFileSlice(downloadUrl string, wg *sync.WaitGroup, mu *sync.Mutex, depth int) {

	client := http.Client{}
	req, err := http.NewRequest("GET", downloadUrl, nil)
	if err != nil {
		log.Error(err)
		os.Exit(1)
	}

	req.Header.Set("Accept", "application/json")
	accessToken, _ := d.Token()
	accessToken.SetAuthHeader(req)

	resp, err := client.Do(req)
	if err != nil {
		log.Error(err)
		os.Exit(1)
	}
	defer resp.Body.Close()

	result := DirectoryDownloadResponse{}
	body, _ := io.ReadAll(resp.Body)
	if err := json.Unmarshal(body, &result); err != nil {
		fmt.Printf("Cannot unmarshal JSON: %d, %v\n", resp.StatusCode, err)
	}

	if len(result.Next) != 0 || result.Next != "" {
		wg.Add(1)
		go d.PopulateFileSlice(result.Next, wg, mu, depth)
	}
	for _, data := range result.Values {
		if data.Type == "commit_file" {
			mu.Lock()
			d.Files = append(d.Files, data.Path)
			mu.Unlock()
		} else if data.Type == "commit_directory" {
			if depth != 0 {
				wg.Add(1)
				downloadUrl := BitbucketUrlBuilder(d.Workspace, d.RepoSlug, d.Ref, data.Path)
				go d.PopulateFileSlice(downloadUrl, wg, mu, depth-1)
				d.addDirectory(data.Path, depth, mu)
			}
		}
	}
	wg.Done()
}

func (d *TargetDownload) addDirectory(value string, depth int, mu *sync.Mutex) {
	if depth <= 0 {
		return
	}
	mu.Lock()
	d.Dirs = append(d.Dirs, value)
	mu.Unlock()
}

func (d *TargetDownload) DownloadFile(filePath string, wg *sync.WaitGroup, guard chan error) {
	defer func() {
		wg.Done()
		<-guard
	}()
	dir := fmt.Sprintf("%s/%s", d.Args.Output, path.Dir(filePath))
	if _, err := os.Stat(dir); os.IsNotExist(err) {
		os.MkdirAll(dir, 0700)
	}

	downloadUrl := BitbucketUrlBuilder(d.Args.Workspace, d.Args.RepoSlug, d.Args.Ref, filePath)

	client := http.Client{}
	req, err := http.NewRequest("GET", downloadUrl, nil)
	if err != nil {
		log.Error(err)
		os.Exit(1)
	}

	req.Header.Set("Accept", "application/json")
	accessToken, _ := d.Token()
	accessToken.SetAuthHeader(req)

	resp, err := client.Do(req)
	if err != nil {
		log.Error(err)
		os.Exit(1)
	}
	defer resp.Body.Close()

	if resp.StatusCode != 200 {
		b, _ := httputil.DumpResponse(resp, true)
		fmt.Printf("Unable to download file %s from git:\n%s\n", filePath, b)
		return
	}

	out, err := os.Create(fmt.Sprintf("%s/%s", d.Args.Output, filePath))
	if err != nil {
		log.Error(err)
		os.Exit(1)
	}
	defer out.Close()

	_, err = io.Copy(out, resp.Body)
	if err != nil {
		log.Error(err)
		os.Exit(1)
	}

}

func BitbucketUrlBuilder(workspace, repoSlug, ref, path string) string {
	// Reference
	// https://developer.atlassian.com/cloud/bitbucket/rest/api-group-source/#api-repositories-workspace-repo-slug-src-commit-path-get
	baseUrl := "https://api.bitbucket.org/2.0/repositories"
	downloadUrl := fmt.Sprintf("%s/%s/%s/src/%s/%s", baseUrl, workspace, repoSlug, ref, path)
	return downloadUrl
}
