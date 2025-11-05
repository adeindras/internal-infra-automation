import * as legalAPI from "../api/legal.js"
import { getTokenViaGrantType } from "../api/iam.js"
import { Dependencies } from "../models/dependencies.ts"
import { checkResponse } from "../lib/utils.js"

export function callTestLegal(baseUrl, namespaceGame, accessToken) {
	checkResponse(legalAPI.getAgreement(baseUrl, accessToken), {
    'get agreements': r => r && r.status === 200
  });
	checkResponse(legalAPI.getEligibilities(baseUrl, namespaceGame, accessToken), {
    'get eligibilities': r => r && r.status === 200
  });
}

export function testLegal(dep:Dependencies) {
  if (!dep.accessToken) {
    dep.accessToken = getTokenViaGrantType(
      dep.configData.baseURLDirect, 
      dep.userData.emailAddress, 
      dep.userData.password, 
      dep.seedData.gameClientId, 
      dep.seedData.gameClientSecret
    )?.toString()!;
  }

  callTestLegal(dep.configData.baseURLDirect, dep.configData.namespaceGame, dep.accessToken)
}