import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatCardModule } from '@angular/material/card';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatChipsModule } from '@angular/material/chips';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import {
  OwnerPublicService,
  OwnerPublicProfile,
} from '../../../../core/services/owner-public.service';
import { AvailabilityFilterDialogComponent } from '../../dialogs/availability-filter-dialog/availability-filter-dialog.component';

@Component({
  selector: 'app-owner-profile-page',
  standalone: true,
  imports: [
    CommonModule,
    RouterLink,
    MatButtonModule,
    MatIconModule,
    MatCardModule,
    MatProgressSpinnerModule,
    MatChipsModule,
    MatDialogModule,
  ],
  templateUrl: './owner-profile-page.component.html',
  styleUrls: ['./owner-profile-page.component.scss'],
})
export class OwnerProfilePageComponent implements OnInit {
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private ownerService = inject(OwnerPublicService);
  private dialog = inject(MatDialog);

  profile = signal<OwnerPublicProfile | null>(null);
  loading = signal<boolean>(true);
  error = signal<string | null>(null);

  ownerId: number | null = null;
  filterStart = signal<string | null>(null);
  filterEnd = signal<string | null>(null);

  ngOnInit() {
    this.route.paramMap.subscribe((params) => {
      const id = params.get('id');
      if (id) {
        this.ownerId = Number(id);

        this.route.queryParams.subscribe((queryParams) => {
          const start = queryParams['start'];
          const end = queryParams['end'];

          this.filterStart.set(start || null);
          this.filterEnd.set(end || null);

          this.loadProfile(this.ownerId!, start, end);
        });
      } else {
        this.error.set('ID vlasnika nije pronađen');
        this.loading.set(false);
      }
    });
  }

  loadProfile(id: number, start?: string, end?: string) {
    this.loading.set(true);
    this.ownerService.getOwnerPublicProfile(id, start, end).subscribe({
      next: (data) => {
        this.profile.set(data);
        this.loading.set(false);
      },
      error: (err) => {
        console.error('Error loading profile', err);
        this.error.set('Neuspešno učitavanje profila vlasnika');
        this.loading.set(false);
      },
    });
  }

  openFilterDialog() {
    const dialogRef = this.dialog.open(AvailabilityFilterDialogComponent);

    dialogRef.afterClosed().subscribe((result) => {
      if (result) {
        const start = result.start.toISOString();
        const end = result.end.toISOString();

        this.router.navigate([], {
          relativeTo: this.route,
          queryParams: { start, end },
          queryParamsHandling: 'merge',
        });
      }
    });
  }

  clearFilter() {
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { start: null, end: null },
      queryParamsHandling: 'merge',
    });
  }
}
