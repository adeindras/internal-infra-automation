import { getTokenViaGrantType, iamGetTokenV3 } from '../api/iam.js';
import * as turnmanagerAPI from '../api/turnmanager.ts';
import { checkResponse } from '../lib/utils.js';
import { Dependencies } from '../models/dependencies.ts';

let clientToken = '';
function callTestTurnmanager(baseurl: string, accessToken: string) {
  const getTurnRes = turnmanagerAPI.getTurnServers(baseurl, accessToken);
  checkResponse(getTurnRes, {
    'get turn servers': r => r && r.status === 200
  });

  const ip = getTurnRes.json('servers.0.ip')?.toString();
  const port = getTurnRes.json('servers.0.port')?.valueOf() as number;
  const region = getTurnRes.json('servers.0.region')?.toString();
  const saveSecretRes = turnmanagerAPI.storeTurnSecret(baseurl, {
    ip: ip,
    port: port,
    region: region,
    secret: 'n07r34LLys3cR37',
    expired: new Date(Date.now()+90000).toISOString()
  }, clientToken);
  checkResponse(saveSecretRes, {
    'saved turn secret': r => r && r.status === 204
  });
}

export function testTurnmanager(dep: Dependencies) {
  if (!dep.adminAccessToken) {
    dep.adminAccessToken = getTokenViaGrantType(
      dep.configData.baseURLDirect, 
      dep.configData.adminEmail, 
      dep.configData.adminPassword, 
      dep.configData.clientId, 
      dep.configData.clientSecret
    )?.toString()!;
  }
  if (!dep.clientToken) {
    const tokenRes = iamGetTokenV3(dep.configData.baseURLDirect, {
      grant_type: 'client_credentials'
    }, dep.seedData.gameClientId, dep.seedData.gameClientSecret);
    
    dep.clientToken = tokenRes.json('access_token')?.toString()!;
  }
  clientToken = dep.clientToken;
  
  callTestTurnmanager(dep.configData.baseURLDirect, dep.adminAccessToken);
}