import * as http from './httpwithretry.js';
import * as utils from '../lib/utils.js'
import encoding from 'k6/encoding';
import crypto from 'k6/crypto';
import urlencode from 'https://jslib.k6.io/form-urlencoded/3.0.0/index.js';
import { URLSearchParams } from 'https://jslib.k6.io/url/1.0.0/index.js';
import { randomString } from 'https://jslib.k6.io/k6-utils/1.2.0/index.js';

export function iamAuthorize(baseUrl, clientId, clientSecret, codeChallenge, code_challenge_method) {
  const queryParams = utils.urlQueryParams({
    response_type: 'code',
    client_id: clientId,
    code_challenge: (code_challenge_method === 'S256') ? crypto.sha256(codeChallenge, 'base64rawurl') : codeChallenge,
    code_challenge_method: code_challenge_method,
  });

  const url = `${baseUrl}/iam/v3/oauth/authorize${queryParams}`;
  const params = {
    headers: { Authorization: `Basic ${encoding.b64encode(`${clientId}:${clientSecret}`)}` },
    tags: { name: iamAuthorize.name },
  };
  
  return http.getWithRedirectedError(url, params);
}

export function iamAuthenticate(baseUrl, email, password, requestId) {
  const url = `${baseUrl}/iam/v3/authenticate`;
  const body = {
    user_name: email,
    password: password,
    request_id: requestId,
  };

  const params = {
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    tags: { name: iamAuthenticate.name },
  };

  return http.postWithRedirectedError(url, body, params);
}

export function iamGetTokenV3(baseUrl, body, clientId, clientSecret) {
  const url = `${baseUrl}/iam/v3/oauth/token`;

  const params = {
    headers: { 
      'Content-Type': 'application/x-www-form-urlencoded',
      Authorization: `Basic ${encoding.b64encode(`${clientId}:${clientSecret}`)}`
    },
    tags: { name: iamGetTokenV3.name },
  };

  return http.post(url, body, params);
}

export function loginViaAuthFlow(baseUrl, email, password, clientId, clientSecret) {
  const codeChallenge = utils.uuid(2);
  const authorize = iamAuthorize(baseUrl, clientId, clientSecret, codeChallenge, 'S256');

  if (authorize.status !== 302) throw new Error(`${authorize.status}: ${authorize.body} ${authorize.error}`);
  const authorizeResult = new URLSearchParams(authorize.headers['Location'].split('?')[1]);
  
  if (!authorizeResult.get('request_id')) throw new Error(`${authorize.status}: ${authorizeResult.toString()}`);
  const request_id = authorizeResult.get('request_id');

  const authenticate = iamAuthenticate(baseUrl, email, password, request_id);
  if (authenticate.status !== 302) throw new Error(`${authenticate.status}: ${authenticate.body} ${authenticate.error}`);
  const result = new URLSearchParams(authenticate.headers['Location'].split('?')[1]);
  const code = result.get('code');
  
  if (!code) throw new Error(`${authenticate.status}: ${result.toString()}`);

  const body = {
    grant_type: 'authorization_code',
    code: code,
    code_verifier: codeChallenge
  };
  return iamGetTokenV3(baseUrl, body, clientId, clientSecret);
}

export function getTokenViaGrantType(pBaseUrl ,pUsername, pUserPassword, pClientId, pClientSecret) {
  const auth = encoding.b64encode(`${pClientId}:${pClientSecret}`);
  const url = `${pBaseUrl}/iam/v3/oauth/token`;
  const body = urlencode({ 
    'grant_type': 'password', 
    'username': `${pUsername}`, 
    'password': `${pUserPassword}`})
  const params = {
    headers: { 
      'accept': 'application/json',
      'Authorization': `Basic ${auth}`,
      'Content-Type': 'application/x-www-form-urlencoded'
    },
    tags: { name: getTokenViaGrantType.name },
  };

  
  let res = http.post(url, body, params);
  return res.json("access_token")
}

export function getProfile(baseUrl, token) {
  const url = `${baseUrl}/iam/v3/public/users/me?includeAllPlatforms=false`;
  const params = {
    headers: { 'accept': 'application/json',
                'Authorization':  `Bearer ${token}`},
    tags: { name: getProfile.name },
  };

  return http.get(url, params);
}

export function updateProfile(baseUrl, namespace, token) {
  const url = `${baseUrl}/iam/v3/public/namespaces/${namespace}/users/me`;
  const params = {
    headers: { 'accept': 'application/json',
                'Authorization': `Bearer ${token}`,
                'Content-Type': 'application/json'},
    tags: { name: updateProfile.name },
  };
  const body = JSON.stringify({
    "displayName": "updatedNameDisplay"
  })

  return http.put(url, body, params);
}

export function updateProfileV4(baseUrl, namespace, token) {
  const url = `${baseUrl}/iam/v4/public/namespaces/${namespace}/users/me`;
  const params = {
    headers: { 'accept': 'application/json',
                'Authorization': `Bearer ${token}`,
                'Content-Type': 'application/json'},
    tags: { name: updateProfileV4.name },
  };
  const body = JSON.stringify({
    "displayName": "updatedNameDisplay"
  })

  return http.patch(url, body, params);
}

export function createTestUser(baseUrl, namespace, token, pUserNum) {
  const url = `${baseUrl}/iam/v4/admin/namespaces/${namespace}/test_users`;
  const params = {
    headers: { 'accept': 'application/json',
                'Authorization': `Bearer ${token}`,
                'Content-Type': 'application/json'},
    tags: { name: createTestUser.name },
  };
  const body = JSON.stringify({
    "count": Number(pUserNum),
    "userInfo": {
      "country": "ID"
    }
  })
  return http.post(url, body, params);
}

export function deleteUser(baseUrl, userId, namespace, token) {
  const url = `${baseUrl}/iam/v3/admin/namespaces/${namespace}/users/${userId}/information`;
  const params = {
    headers: { 'accept': 'application/json',
                'Authorization': `Bearer ${token}`},
    tags: { name: deleteUser.name },
  };

  return http.del(url, null, params);
}

export function deleteClient(baseUrl, namespace, clientId, token) {
  const url = `${baseUrl}/iam/v3/admin/namespaces/${namespace}/clients/${clientId}`;
  const params = {
    headers: { 'accept': 'application/json',
                'Authorization': `Bearer ${token}`},
    tags: { name: deleteClient.name },
  };

  return http.del(url, null, params);
}

export function get3rdPartyToken(baseUrl, platformId, platformToken, pClientId, pClientSecret) {
  const auth = encoding.b64encode(`${pClientId}:${pClientSecret}`);
  const url = `${baseUrl}/iam/v3/oauth/platforms/${platformId}/token`;
  const body = urlencode({ 
    'platform_token': `${platformToken}_ps5_${randomString(5)}`,
    'createHeadless': 'true', 
    'skipSetCookie': 'false'
  })
  const params = {
    headers: { 'accept': 'application/json',
                'Authorization': `Basic ${auth}`,
                'Content-Type': 'application/x-www-form-urlencoded'},
  }
  let res = http.post(url, body, params);
  if (res.status === 200) {
    return res.json("access_token")
  }
  return null
}

export function getClientsByName(baseUrl, namespace, clientName, token) {
  const url = `${baseUrl}/iam/v3/admin/namespaces/${namespace}/clients?=clientName=${clientName}`;
  const params = {
    headers: { 'accept': 'application/json',
                'Authorization': `Bearer ${token}`},
    tags: { name: getClientsByName.name },
  };

  return http.get(url, params);
}

export function createClient(baseUrl, namespace, body, token) {
  const url = `${baseUrl}/iam/v3/admin/namespaces/${namespace}/clients`;
  const params = {
    headers: { 'accept': 'application/json',
                'Authorization': `Bearer ${token}`,
                'Content-Type': 'application/json'},
    tags: { name: createClient.name },
  };
  return http.post(url, JSON.stringify(body), params);
}