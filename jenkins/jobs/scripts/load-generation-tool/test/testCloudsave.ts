import * as cloudsaveAPI from "../api/cloudsave.js"
import { getTokenViaGrantType, getProfile } from "../api/iam.js"
import { checkResponse } from "../lib/utils.js";
import { Dependencies } from "../models/dependencies.js";

export function callTestCloudsave(baseUrl, namespaceGame, userId, gameRecordKey, accessToken, adminAccessToken) {
  checkResponse(cloudsaveAPI.createPlayerRecord(baseUrl, namespaceGame, userId, gameRecordKey, accessToken), {
    'successfully create player record': r => r && r.status === 201
  });
  checkResponse(cloudsaveAPI.updateRecord(baseUrl, namespaceGame, gameRecordKey+userId, adminAccessToken), {
    'successfully update record': r => r && r.status === 200
  });
  checkResponse(cloudsaveAPI.deleteRecord(baseUrl, namespaceGame, gameRecordKey+userId, adminAccessToken), {
    'successfully delete record': r => r && r.status === 204
  });
  cloudsaveAPI.deletePlayerRecord(baseUrl, namespaceGame, userId, gameRecordKey, accessToken);
}

export function testCloudsave (dep: Dependencies) {
  if (!dep.accessToken) {
    dep.accessToken = getTokenViaGrantType(
      dep.configData.baseURLDirect, 
      dep.userData.emailAddress, 
      dep.userData.password, 
      dep.seedData.gameClientId, 
      dep.seedData.gameClientSecret
    )?.toString()!;
  }
  
  if (!dep.adminAccessToken) {
    dep.adminAccessToken = getTokenViaGrantType(
      dep.configData.baseURLDirect, 
      dep.configData.adminEmail, 
      dep.configData.adminPassword, 
      dep.seedData.gameClientId, 
      dep.seedData.gameClientSecret
  )?.toString()!;
   
  }
  const userId = getProfile(dep.configData.baseURLDirect, dep.accessToken).json('userId')?.toString()!;
  callTestCloudsave(dep.configData.baseURLDirect,
    dep.configData.namespaceGame, userId, dep.configData.gameRecordKey, dep.accessToken, dep.adminAccessToken);
}