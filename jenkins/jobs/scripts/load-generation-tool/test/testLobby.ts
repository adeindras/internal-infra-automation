import { getTokenViaGrantType } from '../api/iam.js';
import * as lobbyAPI from '../api/lobby.js';
import { checkResponse } from '../lib/utils.js';
import { Dependencies } from '../models/dependencies.js';

export function callTestLobby(baseUrl:string, namespaceGame:string, accessToken:string) {
  const res = lobbyAPI.getLobbyConfig(baseUrl, namespaceGame, accessToken);
  checkResponse(res, {
    'get lobby config': r => r && r.status === 200
  });
  
  checkResponse(lobbyAPI.updateLobbyConfig(baseUrl, namespaceGame, res.json(), accessToken), {
    'update lobby config': r => r && r.status === 200
  });
}

export function testLobby(dep:Dependencies) {
  if (!dep.adminAccessToken) {
    dep.adminAccessToken = getTokenViaGrantType(
      dep.configData.baseURLDirect, 
      dep.configData.adminEmail, 
      dep.configData.adminPassword, 
      dep.seedData.gameClientId, 
      dep.seedData.gameClientSecret
    )?.toString()!;
  }

  callTestLobby(dep.configData.baseURLDirect, dep.configData.namespaceGame, dep.adminAccessToken);
}