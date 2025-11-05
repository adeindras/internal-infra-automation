package watcher

import (
	"fmt"
	"log"
	"path"
	"regexp"
)

func ParseVersion(input string) (string, error) {
	base := path.Base(input)
	re := regexp.MustCompile(`\d`)

	if re.MatchString(base) {
		return path.Base(input), nil
	}

	dir := path.Dir(input)
	return path.Base(dir), nil
}

func ParseSubVersion(input string) string {
	base := path.Base(input)
	re := regexp.MustCompile(`\d`)

	if re.MatchString(base) {
		return "base"
	}

	return base
}

func GetResourceDetails(namespace string, ks string) (string, string) {
	// get kustomization path
	kustomizationPath, err := GetKustomizationPath("flux-system", ks)
	if err != nil {
		log.Fatalf("Error retrieving Kustomization path: %v", err)
	}

	// extract the version
	version, err := ParseVersion(kustomizationPath)
	if err != nil {
		fmt.Println("Error:", err)
	}

	subversion := ParseSubVersion(kustomizationPath)

	return version, subversion

}
