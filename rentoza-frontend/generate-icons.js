const sharp = require('sharp');
const fs = require('fs');
const path = require('path');

const sizes = [72, 96, 128, 144, 152, 192, 384, 512];
const inputIcon = path.join(__dirname, 'public', 'favicon.ico');
const outputDir = path.join(__dirname, 'public', 'assets', 'icons');

// Ensure output directory exists
if (!fs.existsSync(outputDir)) {
  fs.mkdirSync(outputDir, { recursive: true });
}

// Generate a simple blue circle with white "R" for Rentoza
async function generateIcon(size) {
  const svg = `
    <svg width="${size}" height="${size}" xmlns="http://www.w3.org/2000/svg">
      <rect width="${size}" height="${size}" rx="${size / 8}" fill="#3f51b5"/>
      <text
        x="50%"
        y="50%"
        font-size="${size * 0.6}"
        font-family="Arial, sans-serif"
        font-weight="bold"
        fill="white"
        text-anchor="middle"
        dominant-baseline="central">R</text>
    </svg>
  `;

  const outputPath = path.join(outputDir, `icon-${size}x${size}.png`);
  
  await sharp(Buffer.from(svg))
    .resize(size, size)
    .png()
    .toFile(outputPath);
  
  console.log(`✓ Generated ${size}x${size} icon`);
}

async function generateAllIcons() {
  console.log('🎨 Generating PWA icons...\n');
  
  try {
    for (const size of sizes) {
      await generateIcon(size);
    }
    console.log('\n✅ All icons generated successfully!');
    console.log(`📁 Icons location: ${outputDir}`);
  } catch (error) {
    console.error('❌ Error generating icons:', error);
    process.exit(1);
  }
}

generateAllIcons();
