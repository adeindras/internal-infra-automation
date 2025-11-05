#!/bin/bash

set +x

check_directory_structure() {
    local directory=$1

    # filter resource name
    resource_kind="$(echo "$directory" | awk -F'/' '{print $7}' | grep -vE '\.\w+')"
    resource_name="$(echo "$directory" | awk -F'/' '{print $8}' | grep -vE '\.\w+')"

    case "$resource_kind" in
    rds*)
        if [[ "$resource_kind" != "rds" ]]; then
            echo "NOT_OK"
            return 0
        fi

        if [[ -z "$resource_name" ]]; then
            echo "NOT_OK"
        else
            echo "OK"
        fi
        ;;
    msk*)
        if [[ "$resource_kind" != "msk" ]]; then
            echo "NOT_OK"
            return 0
        fi

        if [[ -z "$resource_name" ]]; then
            echo "NOT_OK"
        else
            echo "OK"
        fi
        ;;
    docdb*)
        if [[ "$resource_kind" != "docdb" ]]; then
            echo "NOT_OK"
            return 0
        fi

        if [[ -z "$resource_name" ]]; then
            echo "NOT_OK"
        else
            echo "OK"
        fi
        ;;
    elasticache*)
        if [[ "$resource_kind" != "elasticache" ]]; then
            echo "NOT_OK"
            return 0
        fi

        if [[ -z "$resource_name" ]]; then
            echo "NOT_OK"
        else
            echo "OK"
        fi
        ;;
    *)
        echo "NOT_OK"
        ;;
    esac
}

check_directory_naming() {
    local directory=$1
    local supported_services=$2

    # filter resource name
    resource_name="$(echo "$directory" | awk -F'/' '{print $8}' | grep -vE '\.\w+')"

    if [[ -z "$resource_name" ]]; then
        echo "NOT_OK"
        return 0
    fi

    case "$resource_name" in
    justice-shared*)
        echo "OK"
        ;;
    analytics-shared*)
        echo "OK"
        ;;
    *)
        IS_SUPPORTED="NO"

        for supported_service in $supported_services; do
            if [[ "$resource_name" =~ ^$supported_service.* ]]; then
                IS_SUPPORTED="YES"
                break
            fi
        done

        if [[ "$IS_SUPPORTED" == "YES" ]]; then
            echo "OK"
        else
            echo "NOT_OK"
        fi
        ;;
    esac
}

filter_directory() {
    local directory=$1

    # env directory format "live/{AWS_ACCOUNT_ID}/{CLIENT_NAME}/{PROJECT_NAME}/{AWS_REGION}/{ENVIRONMENT_NAME}/"
    # filter project name (only justice)(exclude blackbox, ais, devportal, etc.)
    project_name="$(echo "$directory" | awk -F'/' '{print $4}' | grep -vE '\.\w+')"

    if [[ "$project_name" != "justice" ]]; then
        echo "NOT_OK"
    else
        # filter resource name
        resource_kind="$(echo "$directory" | awk -F'/' '{print $7}' | grep -vE '\.\w+')"

        case "$resource_kind" in
        rds*)
            echo "OK"
            ;;
        msk*)
            echo "OK"
            ;;
        docdb*)
            echo "OK"
            ;;
        elasticache*)
            echo "OK"
            ;;
        *)
            echo "NOT_OK"
            ;;
        esac
    fi
}

file_lint_check() {
    local directory=$1
    local bb_access_token=$2
    local changes_commit_hash=$3
    has_format_errors="false"
    output_file=format_issues.txt

    files="$(echo "$directory" | grep -E '\.(tf|hcl)')"
    file_name="$(echo "$files" | awk -F'/' '{print $NF}')"

    if [[ -z "$files" ]]; then
        echo "+ no terragrunt or terraform files found" >>$output_file
        echo "OK"
        return 1
    fi

    curl -sXGET -H "Authorization: Bearer ${bb_access_token}" "https://api.bitbucket.org/2.0/repositories/accelbyte/iac/src/${changes_commit_hash}/$files" >"$file_name"
    if [[ "$file_name" == *.tf ]]; then
        echo "+ checking format for $files with terraform fmt" >>"$output_file"
        result="$(terraform fmt -check "$file_name")"

        if [[ $? != "0" ]]; then
            echo "+ formatting issues found in $files please run (terraform fmt)" >>"$output_file"
            echo "$result" >>"$output_file"
            has_format_errors="true"
        else
            echo "+ file format: OK" >>"$output_file"
            has_format_errors="false"
        fi
    fi

    if [[ "$file_name" == *.hcl ]]; then
        echo "+ checking format for $files with terragrunt hclfmt" >>"$output_file"
        result="$(terragrunt hclfmt --check "$file_name")"

        if [[ $? != 0 ]]; then
            echo "+ formatting issues found in $files please run (terragrunt hclfmt)" >>"$output_file"
            echo "$result" >>"$output_file"
            has_format_errors="true"
        else
            echo "+ file format: OK" >>"$output_file"
            has_format_errors="false"
        fi
    fi

    if [[ "$has_format_errors" == "true" ]]; then
        echo "NOT_OK"
    else
        echo "OK"
    fi
}

main() {
    DIRECTORY=$1
    BB_ACCESS_TOKEN=$2
    CHANGES_COMMIT_HASH=$3
    SUPPORTED_SERVICES_AS_STRING=$4

    SUPPORTED_SERVICES="$(echo "$SUPPORTED_SERVICES_AS_STRING" | tr ',' ' ')"

    IS_OK="$(filter_directory "$DIRECTORY")"
    IS_FILE_LINT_OK="$(file_lint_check "$DIRECTORY" "$BB_ACCESS_TOKEN" "$CHANGES_COMMIT_HASH")"

    if [[ "$IS_FILE_LINT_OK" != "OK" ]]; then
        cat format_issues.txt
        echo "NOT_OK" >>result.txt

        return 0
    fi

    if [[ "$IS_OK" == "OK" ]]; then
        DIRECTORY_STRUCTURE_CHECK_RESULT="$(check_directory_structure "$DIRECTORY")"
        DIRECTORY_NAMING_CHECK_RESULT=$(check_directory_naming "$DIRECTORY" "$SUPPORTED_SERVICES")

        echo "+ checking directory $DIRECTORY"

        echo "+ directory structure: $DIRECTORY_STRUCTURE_CHECK_RESULT"
        echo "+ directory naming: $DIRECTORY_NAMING_CHECK_RESULT"

        if [[ "$DIRECTORY_STRUCTURE_CHECK_RESULT" == "OK" ]] && [[ "$DIRECTORY_NAMING_CHECK_RESULT" == "OK" ]]; then
            cat format_issues.txt
            echo "OK" >>result.txt
        else
            echo "+ correct structure & naming: https://bitbucket.org/accelbyte/iac/src/master/MUST-READ!!!.md"
            echo "NOT_OK" >>result.txt
        fi
    fi
}

main "$@"
