package utils

import (
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
