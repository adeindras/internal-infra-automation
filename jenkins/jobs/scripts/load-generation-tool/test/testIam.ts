import * as iamAPI from "../api/iam.js"
import { sleep, check } from 'k6';
import { Dependencies } from "../models/dependencies.js";
import { checkResponse } from "../lib/utils.js";

export function callTestIAM(baseUrl, namespaceGame, accessToken) {
  checkResponse(iamAPI.getProfile(baseUrl, accessToken), {
    'get iam profile': r => r && r.status === 200
  });
  checkResponse(iamAPI.updateProfileV4(baseUrl, namespaceGame, accessToken), {
    'update iam profile': r => r && r.status === 200
  });
}

export function testIAM(dep:Dependencies) {
  const res = iamAPI.loginViaAuthFlow(
    dep.configData.baseURLDirect, 
    dep.userData.emailAddress, 
    dep.userData.password, 
    dep.seedData.gameClientId, 
    dep.seedData.gameClientSecret
  );
  if (res) dep.accessToken = res.json('access_token')?.toString()!;
  callTestIAM(dep.configData.baseURLDirect, dep.configData.namespaceGame, dep.accessToken);
}

export function deleteUsers(dep:Dependencies) {
  if (!dep.accessToken) {
    dep.accessToken = iamAPI.getTokenViaGrantType(
      dep.configData.baseURLDirect, 
      dep.configData.adminEmail, 
      dep.configData.adminPassword, 
      dep.configData.clientId, 
      dep.configData.clientSecret
    )?.toString()!;
  }

  dep.userData.forEach(user => {
    const res = iamAPI.deleteUser(dep.configData.baseURLDirect, user.userId, dep.configData.namespaceGame, dep.accessToken);
    if(!check(res, {
      'response status is 204': r => r.status === 204
    }, {name: 'delete_user'})) {
      console.error('unexpected response:', res);
    }
    sleep(0.2);
  });
}

export function deleteClients(dep:Dependencies) {
  if (!dep.accessToken) {
    dep.accessToken = iamAPI.getTokenViaGrantType(
      dep.configData.baseURLDirect, 
      dep.configData.adminEmail, 
      dep.configData.adminPassword, 
      dep.configData.clientId, 
      dep.configData.clientSecret
    )?.toString()!;
  }
  dep.seedData.forEach(client => {
    const res = iamAPI.deleteClient(dep.configData.baseURLDirect, dep.configData.namespaceGame, client.gameClientId, dep.accessToken);
    if(!check(res, {
      'response status is 204': r => r.status === 204
    }, {name: 'delete_client'})) {
      console.error('unexpected reponse', res);
    }
    sleep(2);
  });
}