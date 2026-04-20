import { Injectable } from '@angular/core';

@Injectable({
  providedIn: 'root'
})
export class ExportService {

  constructor() { }

  /**
   * Converts an array of objects to CSV and triggers a download.
   * @param data Array of objects to export
   * @param filename Desired filename (without extension)
   */
  exportToCsv(data: any[], filename: string): void {
    if (!data || !data.length) {
      console.warn('ExportService: No data to export');
      return;
    }

    const separator = ',';
    const keys = Object.keys(data[0]);
    
    const csvContent = [
      keys.join(separator), // Header row
      ...data.map(row => {
        return keys.map(k => {
          let cell = row[k] === null || row[k] === undefined ? '' : row[k];
          cell = cell instanceof Date ? cell.toISOString() : cell.toString();
          // Escape quotes and wrap in quotes if separator is present
          if (cell.search(/("|,|\n)/g) >= 0) {
            cell = `"${cell.replace(/"/g, '""')}"`;
          }
          return cell;
        }).join(separator);
      })
    ].join('\n');

    this.downloadFile(csvContent, `${filename}.csv`, 'text/csv;charset=utf-8;');
  }

  private downloadFile(content: string, filename: string, mimeType: string) {
    const blob = new Blob([content], { type: mimeType });
    
    // Create link and simulate click
    const link = document.createElement('a');
    if (Object.prototype.hasOwnProperty.call(link, 'download')) {
      const url = URL.createObjectURL(blob);
      link.setAttribute('href', url);
      link.setAttribute('download', filename);
      link.style.visibility = 'hidden';
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);
    }
  }
}