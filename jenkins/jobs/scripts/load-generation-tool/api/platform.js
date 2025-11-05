import * as http from './httpwithretry.js';

export function getCurrencies(baseUrl, namespace, token) {
  const url = `${baseUrl}/platform/public/namespaces/${namespace}/currencies`;
  const params = {
    headers: { 'accept': 'application/json',
                'Content-Type': 'application/json',
                'Authorization':  `Bearer ${token}`},
    tags: { name: getCurrencies.name },
  };
  const res = http.get(url, params);
  const currencies = JSON.parse(res.body);
  return currencies;
}

export function getStores(baseUrl, namespace, token) {
  const url = `${baseUrl}/platform/public/namespaces/${namespace}/stores`;
  const params = {
    headers: { 'accept': 'application/json',
                'Content-Type': 'application/json',
                'Authorization':  `Bearer ${token}`},
    tags: { name: getStores.name },
  };

  return http.get(url, params);
}

export function updateCurrencies(baseUrl, namespace, currencyCode, token) {
  const url = `${baseUrl}/platform/admin/namespaces/${namespace}/currencies/${currencyCode}`;
  const params = {
    headers: {  'accept': 'application/json',
                'Content-Type': 'application/json',
                'Authorization': `Bearer ${token}`},
    tags: { name: updateCurrencies.name },
  };
  const body = JSON.stringify({
    "localizationDescriptions": {
      "en": "test"
    }
  })

  return http.put(url, body, params);
}

export function creditUserWallet(baseUrl, namespace, currencyCode, userId, expireDate, token) {
  const url = `${baseUrl}/platform/admin/namespaces/${namespace}/users/${userId}/wallets/${currencyCode}/credit`;
  const params = {
    headers: {  'accept': 'application/json',
                'Content-Type': 'application/json',
                'Authorization': `Bearer ${token}`},
    tags: { name: creditUserWallet.name },
  };
  const body = JSON.stringify({
    "amount": 1,
    "source": "PURCHASE",
    "expireAt": `${expireDate}`,
    "reason": "test",
    "origin": "Other"
  })

  return http.put(url, body, params);
}

export function getUserWalletTransaction(baseUrl, namespace, userId, currencyCode, token) {
  const url = `${baseUrl}/platform/public/namespaces/${namespace}/users/${userId}/wallets/${currencyCode}/transactions?offset=0&limit=20`;
  const params = {
    headers: { 'accept': 'application/json',
                'Authorization':  `Bearer ${token}`},
    tags: { name: getUserWalletTransaction.name },
  };

  return http.get(url, params);
}