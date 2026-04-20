#!/usr/bin/env node

/**
 * Post-build script to remove console statements from production bundles.
 * 
 * Strategy: Replace console.log/debug/info with void 0
 * This is safe because:
 * - void 0 is a valid expression (evaluates to undefined)
 * - void 0 works in all contexts: statements, expressions, comma operators, etc.
 * 
 * Usage: node remove-console-logs.js <dist-path>
 */

const fs = require('fs');
const path = require('path');

const distPath = process.argv[2] || 'dist/rentoza-frontend/browser';

if (!fs.existsSync(distPath)) {
  console.error(`❌ Distribution directory not found: ${distPath}`);
  process.exit(1);
}

let totalRemoved = 0;
let filesProcessed = 0;

/**
 * Replaces console.log/debug/info statements with void 0.
 * Tracks parentheses depth to handle nested function calls.
 */
function removeConsoleStatements(content) {
  const pattern = /console\.(log|debug|info)\s*\(/g;
  let result = '';
  let lastIndex = 0;
  let match;
  
  while ((match = pattern.exec(content)) !== null) {
    // Add everything before this match
    result += content.slice(lastIndex, match.index);
    
    // Find the matching closing parenthesis
    let depth = 1;
    let i = match.index + match[0].length;
    
    while (i < content.length && depth > 0) {
      const char = content[i];
      if (char === '(') depth++;
      else if (char === ')') depth--;
      i++;
    }
    
    // Replace with void 0 (always safe in any context)
    result += 'void 0';
    
    lastIndex = i;
  }
  
  // Add remaining content
  result += content.slice(lastIndex);
  
  return result;
}

function removeConsoleLogs(filePath) {
  let content = fs.readFileSync(filePath, 'utf8');
  const originalLength = content.length;
  
  // Remove console.log, console.debug, console.info statements
  content = removeConsoleStatements(content);
  
  const removed = originalLength - content.length;
  
  if (removed > 0) {
    fs.writeFileSync(filePath, content, 'utf8');
    totalRemoved += removed;
    return true;
  }
  
  return false;
}

function processDirectory(dir) {
  const items = fs.readdirSync(dir);
  
  for (const item of items) {
    const fullPath = path.join(dir, item);
    const stat = fs.statSync(fullPath);
    
    if (stat.isDirectory()) {
      processDirectory(fullPath);
    } else if (item.endsWith('.js')) {
      filesProcessed++;
      if (removeConsoleLogs(fullPath)) {
        console.log(`✅ Cleaned: ${path.relative(distPath, fullPath)}`);
      }
    }
  }
}

console.log(`\n🧹 Removing console.log statements from ${distPath}...\n`);

processDirectory(distPath);

console.log(`\n✨ Done!`);
console.log(`📊 Processed ${filesProcessed} JavaScript files`);
console.log(`🗑️  Removed ${(totalRemoved / 1024).toFixed(2)} KB of console statements`);
