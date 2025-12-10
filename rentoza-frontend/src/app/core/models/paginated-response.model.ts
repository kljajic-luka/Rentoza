export interface HateoasLink {
  href: string;
  templated?: boolean;
}

export interface HateoasPage<T> {
  _embedded?: {
    content?: T[];
  };
  page?: {
    size: number;
    totalElements: number;
    totalPages: number;
    number: number;
  };
  _links?: {
    first?: HateoasLink;
    self?: HateoasLink;
    next?: HateoasLink;
    prev?: HateoasLink;
    last?: HateoasLink;
    [rel: string]: HateoasLink | undefined;
  };
}

export interface PaginatedResponse<T> {
  content: T[];
  totalElements: number;
  page: number;
  size: number;
  links?: HateoasPage<T>['_links'];
}
