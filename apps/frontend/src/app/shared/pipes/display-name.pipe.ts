import { Pipe, PipeTransform } from '@angular/core';

@Pipe({
  name: 'displayName',
  standalone: true
})
export class DisplayNamePipe implements PipeTransform {
  transform(value?: { firstName?: string; lastName?: string; email?: string } | null): string {
    if (!value) {
      return '';
    }

    const fullName = [value.firstName, value.lastName]
      .filter((part) => Boolean(part && part.trim().length))
      .join(' ')
      .trim();

    return fullName.length ? fullName : value.email ?? '';
  }
}