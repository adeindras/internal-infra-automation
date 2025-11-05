import * as configAPI from "../api/config.js"
import { getTokenViaGrantType, getProfile } from "../api/iam.js"
import { checkResponse } from "../lib/utils.js";
import { Dependencies } from "../models/dependencies.js";

export function callTestConfig(baseUrl, namespaceGame, accessToken) {
	checkResponse(configAPI.getConfig(baseUrl, namespaceGame, accessToken), {
    'get config': r => r && r.status === 200
  });
}

export function testConfig(dep:Dependencies) {
  if (!dep.accessToken) {
    dep.accessToken = getTokenViaGrantType(
      dep.configData.baseURLDirect, 
      dep.userData.emailAddress, 
      dep.userData.password, 
      dep.seedData.gameClientId, 
      dep.seedData.gameClientSecret
    )?.toString()!;
  }

  callTestConfig(dep.configData.baseURLDirect, dep.configData.namespaceGame, dep.accessToken)
}