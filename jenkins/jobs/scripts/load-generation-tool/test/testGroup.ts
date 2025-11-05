import * as groupAPI from "../api/group.js"
import { getTokenViaGrantType, getProfile } from "../api/iam.js"
import { checkResponse } from "../lib/utils.js";
import { Dependencies } from "../models/dependencies.js";

export function callTestGroup(baseUrl, namespaceGame, accessToken) {
  checkResponse(groupAPI.getGroup(baseUrl, namespaceGame, accessToken), {
    'get group': r => r && r.status === 200
  });
}

export function testGroup(dep:Dependencies) {
  if (!dep.accessToken) {
    dep.accessToken = getTokenViaGrantType(
      dep.configData.baseURLDirect, 
      dep.userData.emailAddress, 
      dep.userData.password, 
      dep.seedData.gameClientId, 
      dep.seedData.gameClientSecret
    )?.toString()!;
  }
  callTestGroup(dep.configData.baseURLDirect, dep.configData.namespaceGame, dep.accessToken);
}