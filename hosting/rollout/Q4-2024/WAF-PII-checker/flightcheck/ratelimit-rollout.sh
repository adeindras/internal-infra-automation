#!/bin/sh

set -euo pipefail

preflight() {
  printf "${YELLOW}----->${LIGHT_BLUE} Ratelimit Rollout Preflight Checks ${YELLOW}<-----${NC}\n"
  printf "${LIGHT_BLUE}Checking Kubeconfig Context${NC}\n"
  kubectl config current-context
  printf "\n"

  printf "${LIGHT_BLUE}Checking Critical Workload Nodes${NC}\n"
  kubectl get nodes -l accelbyte.io/workload=CriticalWorkloads --no-headers
  printf "\n"
  
  printf "${LIGHT_BLUE}Checking Emissary Version${NC}\n"
  kubectl get deployment -l app.kubernetes.io/part-of=emissary-ingress -n emissary -o=jsonpath='{range .items[*]}{.spec.template.spec.containers[:1].image}{"\n"}'
  printf "\n"
  
  printf "${LIGHT_BLUE}Checking Emissary Status${NC}\n"
  kubectl get pod -n emissary -l app.kubernetes.io/part-of=emissary-ingress --no-headers
  printf "\n"
  
}

postflight() {
  printf "${YELLOW} ----->${LIGHT_BLUE} Ratelimit Rollout Postflight Checks ${YELLOW}<-----${NC}\n"
  
  printf "${LIGHT_BLUE}Checking Ratelimit Status${NC}\n"
  RATELIMIT_POD="$(kubectl get pod -n emissary -l app=ratelimit --no-headers --ignore-not-found)"
  if [[ -z "${RATELIMIT_POD}" ]]
    then
      printf "${RED} --> ratelimit not running${NC}\n"
    else
      printf "${GREEN} --> ratelimit is running${NC}\n"
      printf "${RATELIMIT_POD}\n"
  fi
  
  printf "${LIGHT_BLUE}Checking Redis Status${NC}\n"
  REDIS_POD="$(kubectl get pod -n redis-ratelimit -l app.kubernetes.io/name=redis --no-headers --ignore-not-found)"
  if [[ -z "${REDIS_POD}" ]]
    then
      printf "${RED} --> Redis Ratelimit not running${NC}\n"
    else
      printf "${GREEN} --> Redis Ratelimit is running${NC}\n"
      printf "${REDIS_POD}\n"
  fi
  
  printf "${LIGHT_BLUE}Checking Ratelimit Mode${NC}\n"
  RATELIMIT_MODE="$(kubectl get deploy ratelimit -n emissary -ojsonpath='{range .spec.template.spec.containers[1].env[*]}{.name}{": "}{.value}{"\n"}' | grep -E '(SHADOW_MODE)')"
  printf "${GREEN} ${RATELIMIT_MODE} ${NC}\n"

  printf "${LIGHT_BLUE}Checking Emissary Labels${NC}\n"
  MAPPINGS="$(kubectl get mappings -n justice -o=jsonpath='{range .items[*]}{.metadata.name}{"\n"}')"
  for mapping in ${MAPPINGS}; do 
    LABEL=$(kubectl get mappings $mapping -n justice -ojsonpath='{.spec.labels}')
    if [[ -z "${LABEL}" ]]; then 
      printf "${RED} ${mapping} does not have labels${NC}\n"
    elif [ "${LABEL}" == '{"ambassador":[{"defaults":[{"Host":{"header":"Host"}},"destination_cluster"]}]}' ]; then
      printf "${GREEN} ${mapping} label is CORRECT ${NC}\n"
    else
      printf "${RED} ${mapping} label is NOT CORRECT --> ${LABEL}${NC}\n"
    fi
  done
  
}