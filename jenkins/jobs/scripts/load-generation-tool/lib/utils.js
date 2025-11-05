import { check } from "k6";
import short from "./short-uuid.js";
import { setTimeout } from "k6/timers";

let errorMetrics;

export function setErrorMetrics(metrics) {
  errorMetrics = metrics;
}

export function wsMessageToJSON(input) {
  const lines = input.split("\n").filter(Boolean);
  const outputJSON = {};
  lines.forEach(line => {
    const [keyWithQuotes, ...valuesWithQuotes] = line.split(": ");
    const key = keyWithQuotes.replace(/^"|"$/g, "").trim();
    const value = valuesWithQuotes.join(": ").replace(/\\n(?!$)|"$/g, "").trim();
    if (key && value) {
      outputJSON[key] = value;
    }
  });
  return outputJSON;
}

export function uuid(scale=1) {
    let result = '';
    for (let i = 0; i < scale; i++) {
        result += short.uuid();
    }
    return result.replace(/-/g, "");
}

export function urlQueryParams(queryParams) {
    return Object.entries(queryParams).reduce((a, b) => `${a}${b[0]}=${b[1]}&`, '?');
}

/**
 * durationStringToMillis convert duration string such as 1h30m2s
 * into 5_402_000 in number typed data. Currently only support (h)our, (m)inute, and (s)econd
 * @param {string} duration 
  * @returns {number} durationMillis
 */
export function durationStringToMillis(duration) {
  let temp = '';
  let result = 0;
  outerloop: for (let i = 0; i < duration.length; i++) {
    const c = duration[i].charCodeAt();
    switch(true) {
      case c > 47 && c < 58:
        temp += duration[i];
        continue;
      case c > 64 && c < 91:
      case c > 96 && c < 123:
        switch(duration[i]) {
          case 's':
            result += temp * 1000;
            temp = '';
            continue;
          case 'm':
            result += temp * 60 * 1000;
            temp = '';
            continue;
          case 'h':
            result += temp * 60 * 60 * 1000;
            temp = '';
            continue;
          default:
            result = NaN;
            break outerloop;
        }
      default:
        result = NaN;
        break outerloop;
    }
  }
  return result;
}

export function checkResponse(response, sets, tags={}) {
  const checkResult = check(response, sets, tags);
  if(checkResult) return checkResult;
  response || console.error('user:', __VU, 'empty response');
  console.error('user:', __VU, response.request && response.request.method || response.method, response.url, response.status, response.body || response.error);
  errorMetrics && errorMetrics.add(1, {
    error: response.body || response.error,
    statusCode: `${response.status}`,
    endpoint: `${response.request?response.request.method:null} ${response.url}`,
    vu: __VU.toString()
  });
}

export function createTimer(duration) {
  let _resolve;
  const waiting = new Promise(resolve => {
    _resolve = resolve;
    setTimeout(() => {
      resolve();
    }, duration * 1000);
  });
  return {
    done: waiting,
    abort: _resolve
  }
}