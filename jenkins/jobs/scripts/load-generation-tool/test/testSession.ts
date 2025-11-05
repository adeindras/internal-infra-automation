import { Dependencies } from "../models/dependencies.ts";
import * as sessionAPI from "../api/session.js"
import { getTokenViaGrantType } from "../api/iam.js"
import { checkResponse } from "../lib/utils.js";

export function callTestSession(baseUrl, namespaceGame, sessionTemplate, accessToken) {
  const resCreateGameSession = sessionAPI.createGameSession(baseUrl, namespaceGame, {
    configurationName: sessionTemplate
  }, accessToken);
  checkResponse(resCreateGameSession, {
    'created game session': r => r && r.status === 201
  });
  const sessionId = resCreateGameSession.json('id');
  const sessionVersion:number = parseInt(resCreateGameSession.json('version')?.toString()!);
  
  const myGameSessionRes = sessionAPI.getMyGameSession(baseUrl, namespaceGame, accessToken); 
  checkResponse(myGameSessionRes, {
    'get my game session': r => r && r.status === 200
  });
  
  checkResponse(sessionAPI.updateGameSession(baseUrl, namespaceGame, sessionId, {
    version: sessionVersion,
    joinability: 'CLOSED'
  }, accessToken), {
    'updated game session': r => r && r.status === 200
  });

  checkResponse(sessionAPI.deleteGameSession(baseUrl, namespaceGame, sessionId, accessToken), {
    'deleted game session': r => r && r.status === 204
  });
}

export function testSession(dep:Dependencies) {
  if (!dep.accessToken) {
    dep.accessToken = getTokenViaGrantType(
      dep.configData.baseURLDirect, 
      dep.userData.emailAddress, 
      dep.userData.password, 
      dep.seedData.gameClientId, 
      dep.seedData.gameClientSecret
    )?.toString()!;
  }

  callTestSession(dep.configData.baseURLDirect, dep.configData.namespaceGame, dep.configData.sessionTemplate, dep.accessToken);
}