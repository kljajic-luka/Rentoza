#!/usr/bin/env node

/*
 * CI guard for credentialless staging payments.
 *
 * Fails if staging artifacts contain production Monri mode, or if
 * allowMockInThisEnv isn't compiled as true.
 */
const fs = require('fs');
const path = require('path');

const DIST_DIR = path.resolve(__dirname, '..', 'dist', 'rentoza-frontend', 'browser');

function fail(message) {
  console.error(`[staging-payment-guard] ${message}`);
  process.exit(1);
}

if (!fs.existsSync(DIST_DIR)) {
  fail(`Build output not found: ${DIST_DIR}. Run staging build first.`);
}

const jsFiles = fs.readdirSync(DIST_DIR).filter((name) => name.endsWith('.js'));
if (jsFiles.length === 0) {
  fail(`No JS artifacts found in ${DIST_DIR}.`);
}

const artifact = jsFiles
  .map((name) => fs.readFileSync(path.join(DIST_DIR, name), 'utf8'))
  .join('\n');

const hasMonriMode = artifact.includes('providerMode:"monri"');
const hasMockMode = artifact.includes('providerMode:"mock"');
const hasAllowMockTrue =
  artifact.includes('allowMockInThisEnv:!0') || artifact.includes('allowMockInThisEnv:true');

if (hasMonriMode) {
  fail('Staging bundle contains providerMode:"monri". Staging must stay in mock mode.');
}

if (!hasMockMode) {
  fail('Staging bundle missing providerMode:"mock".');
}

if (!hasAllowMockTrue) {
  fail('Staging bundle missing allowMockInThisEnv:true.');
}

console.log('[staging-payment-guard] PASS: staging bundle enforces mock payment mode.');
