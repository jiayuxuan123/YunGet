import './altcha-3.5.0.js';
import './zh-cn-3.5.0.js';
globalThis.$altcha.algorithms.set('PBKDF2/SHA-256', () => new Worker('/verification/pbkdf2-3.5.0.js'));
