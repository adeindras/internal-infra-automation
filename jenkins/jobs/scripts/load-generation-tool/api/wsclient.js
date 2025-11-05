import { WebSocket } from 'k6/experimental/websockets';
import { setInterval, clearInterval, setTimeout, clearTimeout } from 'k6/timers';

const wsCloseCodeRe = /websocket: close (?=[1-3][0-9]{2}[1-9]|4000|timeout)/; // TODO: add websocket close code checking on error connection

export default class WSClient {
  #isConnected = false;
  #isClosed = false;
  #waitForPong = false;
  #isConnectionTimeout = false;
  #ws;
  #unreadMessages = [];
  #readMessages = [];
  #backoffDuration = 2;
  #retryCount = 0;
  #errors = [];
  #timers = [];
  #intervals = [];
  #errorMetric;
  #retryMetric;
  #disconnectMetric;
  #failedRetryMetric;
  #tags;

  constructor(url, accessToken, reconnect = true, maxBackoffDuration = 30, headers = {}, tags = {}, pingInterval = 5) {
    this.url = url;
    this.accessToken = accessToken;
    this.reconnect = reconnect;
    this.maxBackoffDuration = maxBackoffDuration;
    this.headers = headers;
    this.pingInterval = pingInterval; // in second(s)
    this.#tags = tags;
  }
  
  get isConnected() {
    return this.#isConnected;
  }
  
  set isConnected(value) {
    this.#isConnected = value;
    this.#isClosed = !value;
  }
  
  get readMessages() {
    return this.#readMessages;
  }
  
  get unreadMessages() {
    return this.#unreadMessages;
  }
  
  get retryCount() {
    return this.#retryCount;
  }
  
  get errors() {
    return this.#errors;
  }
  
  get gaveUpRetrying() {
    return this.#backoffDuration >= this.maxBackoffDuration;
  }
  
  setMetrics(metric) {
    this.#errorMetric = metric.errorMetric;
    this.#retryMetric = metric.retryMetric;
    this.#failedRetryMetric = metric.failedRetryMetric;
    this.#disconnectMetric = metric.disconnectMetric;
  }
  
  connect() {
    if (this.#ws) {
      this.#ws = undefined;
    }
    
    // set timeout for making a connection
    const connTimeout = setTimeout(() => {
      if (this.#isConnected) return;
      this.#errors.push('websocket: close timeout');
      this.#isConnectionTimeout = true;
    }, 60000);
    this.#timers.push(connTimeout);

    return new Promise((resolve) => {
      this.headers['Authorization'] = `Bearer ${this.accessToken}`;
      const ws = new WebSocket(this.url, null, {headers: this.headers});
      
      // handle connection established
      ws.onopen = () => {
        this.isConnected = true;

        // handle incoming message event
        ws.onmessage = e => {
          this.#unreadMessages.push(e);
        };
        
        // handle websocket error message
        ws.onerror = e => {
          this.#errors.push(e.error);
          this.#errorMetric.add(1, {error: e.error, vu: `${__VU}`});
          this.#disconnectMetric.add(1, {error: e.error, vu: `${__VU}`});
          this.isConnected = false;
        };
        
        // handle ping for connectivity
        const heartbeat = setInterval(() => {
          if ((this.isConnected && this.#waitForPong) || this.#isClosed) {
            clearInterval(heartbeat);
            this.#errors.push('websocket: close timeout ping');
            this.#isConnected = false;
            return;
          }
          ws.readyState == 1 && ws.ping();
          this.#waitForPong = true;
        }, this.pingInterval * 1000);
        this.#intervals.push(heartbeat);
        
        // handle pong for connectivity
        ws.onpong = () => this.#waitForPong = false;
        
        // handle graceful disconnect
        ws.onclose = () => this.isConnected = false;
        
        this.#ws = ws;
      };

      // handle reconnect
      resolve(new Promise((resolve) => {
        // periodically check the connection, and will reconnect if it's disconnected or timeout
        const connectionCheck = setInterval(() => {
          if (this.isConnected || !this.#isConnectionTimeout) return;
          clearInterval(heartbeat);
          clearInterval(connectionCheck);
          this.#isConnectionTimeout = false;
          resolve();
        }, 100);
        this.#intervals.push(connectionCheck);
        }).then(() => new Promise((resolve) => {
          // reconnect only when there's websocket close 1001-4000 or timeout
          if (this.#errors.length === 0) return;
          this.errors.forEach(err => console.error('error: ', err));
          const cannotRetry = !this.errors.map(err => wsCloseCodeRe.test(err)).reduce((a,b) => a && b);
          if (cannotRetry || this.#backoffDuration >= this.maxBackoffDuration) {
            this.errors.push(`failed to reconnect, retry count: ${this.#retryCount} time(s)`);
            this.#failedRetryMetric.add(1, {...this.#tags, error: this.errors.reduce((p, c) => `${p};${c}`), vu: `${__VU}`});
            console.error(`user ${__VU} failed to reconnect, retry count: ${this.#retryCount} time(s)`);
            cannotRetry && console.error('error code is outside of range 1001-4000');
            return;
          }
          const backoff = this.#backoffDuration.valueOf();
          const jitter = backoff * Math.random() / 2;
          const timer = setTimeout(() => {
            this.#retryMetric.add(1, {...this.#tags, vu: __VU.toString(), prot: 'ws', error: this.errors.reduce((p, c) => `${p};${c}`)});
            console.info(`reconnecting, retry with exponential backoff count: ${this.retryCount}, backoff: ${this.#backoffDuration}s`)
            this.#backoffDuration*=2;
            this.connect();
            resolve();
          }, (backoff + jitter) * 1000);
          this.#timers.push(timer);
          this.#retryCount++;
        })));
    });
  }
  
  disconnect() {
    this.#isConnected = false;
    this.#errors = [];
    if (this.#ws) this.#ws.close();
    this.#intervals.forEach(interval => clearInterval(interval));
    this.#timers.forEach(timeout => clearTimeout(timeout));
  }
  
  nextMessage() {
    const message = this.#unreadMessages.shift();
    this.readMessages.push(message);
    return message;
  }
  
  async waitMessage(predicate = (msg) => true, waitTime = 2) {
    let message = this.#unreadMessages.shift();
    this.readMessages.push(message);
    let waitTicker;

    await new Promise((resolve) => {
      const waitTimer = setTimeout(() => {
        waitTicker && clearInterval(waitTicker);
        message = {error: `user ${__VU}: message not found, predicate: ${predicate}`};
        resolve();
      }, waitTime * 1000);

      this.#timers.push(waitTimer);

      waitTicker = setInterval(() => {
        if (predicate(message)) {
          clearTimeout(waitTimer);
          resolve(clearInterval(waitTicker));
          return;
        }
        message = this.#unreadMessages.shift();
        message && this.readMessages.push(message);
      }, 1);
      this.#intervals.push(waitTicker);
    });

    return message;
  }
  
  sendMessage(message) {
    if (this.#ws && this.isConnected) this.#ws.send(message);
  }
}