# Articulorium Documentation Site

This directory contains the static documentation site for Articulorium, built as a GitHub Pages site.

## Structure

- `index.html` - Main landing page
- `styles.css` - CSS styling
- `script.js` - JavaScript functionality
- `architecture.png` - Architecture diagram (placeholder)

## Development

To develop the documentation site locally:

1. Serve the files using any static server:
   ```bash
   # Using Python (if available)
   python -m http.server 8000
   
   # Or using Node.js
   npx serve docs
   ```

2. Make changes to the HTML, CSS, or JavaScript files

3. The site will be automatically deployed to GitHub Pages on pushes to the main branch.

## Deployment

The site is automatically deployed to GitHub Pages using GitHub Actions when changes are pushed to the main branch.

## Contributing

Contributions to improve this documentation site are welcome. Please submit pull requests with improvements.