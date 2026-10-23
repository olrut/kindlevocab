import { mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { openDb } from './index.js';

const dataDir = process.env.DATA_DIR ?? './data';
mkdirSync(dataDir, { recursive: true });
openDb(join(dataDir, 'vocab.db'));
console.log(`Migrations applied to ${join(dataDir, 'vocab.db')}`);
