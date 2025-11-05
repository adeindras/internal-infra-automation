import { sleep } from 'k6';
import k6_http from 'k6/http';
import { URLSearchParams } from 'https://jslib.k6.io/url/1.0.0/index.js';

let maxBackoffDuration = 30;
let retryMetrics;
let timeoutMetrics;
let failedRetryMetric;

export function setMaxBackoffDuration(duration) {
  maxBackoffDuration = duration;
}

export function setTimeoutMetrics(metric) {
  timeoutMetrics = metric;
}

export function setRetryMetrics(metric) {
  retryMetrics = metric;
}

export function setFailedMetrics(metric) {
  failedRetryMetric = metric;
}

export function retryRequest(method, url, body, params, checkFailFunc = (r) => false) {
  let isTimeout = false;
  let backoff = 2;
  let res = k6_http.request(method, url, body, params);
  isTimeout = res.error.indexOf('request timeout') > -1;
  let isFailed = checkFailFunc(res) || isTimeout || res.status >= 500;
  while (backoff <= maxBackoffDuration && isFailed) {
    sleep(backoff);
    console.warn('user:', __VU, 'retrying', params.tags.name, 'after failed request, backoff: ', backoff);
    let resRetry = k6_http.request(method, url, body, params);
    isTimeout = resRetry.error.indexOf('request timeout') > -1;
    isFailed = checkFailFunc(resRetry) || isTimeout || resRetry.status >= 500;
    backoff *= 2;
    retryMetrics && retryMetrics.add(1, {name: params.tags.name ?? 'http', prot: 'http', error: `${res.body ?? res.error}`, statusCode: res.status.toString(), vuid: __VU.toString()});
    isTimeout && timeoutMetrics && timeoutMetrics.add(1, {name: params.tags.name, error: `${res.body ?? res.error}`, statusCode: res.status.toString(), vuid: __VU.toString()});
    res = resRetry;
  }
  if (isFailed) failedRetryMetric && failedRetryMetric.add(1, {name: params.tags.name, error: `${res.body ?? res.error}`, statusCode: res.status.toString(), vuid: __VU.toString()});
  return res;
}

export function get(url, params) {
  return retryRequest('GET', url, null, params);
}

export function getWithRedirectedError(url, params) {
  return retryRequest('GET', url, null, params, (r) => {
    if (r.status !== 302) return true;
    const location = r.headers['Location'];
    if (!location) return true;
    const res = new URLSearchParams(location.split('?')[1]);
    return res.get('error');
  });
}

export function postWithRedirectedError(url, body, params) {
  return retryRequest('POST', url, body, params, (r) => {
    if (r.status !== 302) return true;
    const location = r.headers['Location'];
    if (!location) return true;
    const res = new URLSearchParams(location.split('?')[1]);
    return res.get('error');
  });
}

export function put(url, body, params) {
  return retryRequest('PUT', url, body, params);
}

export function patch(url, body, params) {
  return retryRequest('PATCH', url, body, params);
}

export function post(url, body, params) {
  return retryRequest('POST', url, body, params);
}

export function del(url, body, params) {
  return retryRequest('DELETE', url, body, params);
}