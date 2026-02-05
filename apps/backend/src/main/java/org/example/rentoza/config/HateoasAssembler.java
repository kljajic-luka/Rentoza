package org.example.rentoza.config;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.web.PagedResourcesAssembler;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.PagedModel;
import org.springframework.stereotype.Component;

/**
 * Enterprise utility for converting Page results to HATEOAS PagedModel.
 * 
 * Usage in controllers:
 * 
 * @GetMapping("/api/admin/users")
 * public PagedModel<EntityModel<AdminUserDetailDto>> listUsers(Pageable pageable) {
 *     Page<User> page = userRepository.findAll(pageable);
 *     return hateoasAssembler.toModel(page, AdminUserDetailDto::fromEntity);
 * }
 * 
 * Result JSON structure:
 * {
 *   "_embedded": {
 *     "content": [...]
 *   },
 *   "page": {
 *     "size": 20,
 *     "totalElements": 150,
 *     "totalPages": 8,
 *     "number": 0
 *   },
 *   "_links": {
 *     "first": { "href": "..." },
 *     "next": { "href": "..." },
 *     "last": { "href": "..." },
 *     "self": { "href": "..." }
 *   }
 * }
 */
@Component
@RequiredArgsConstructor
public class HateoasAssembler {
    
    private final PagedResourcesAssembler<Object> assembler;
    
    /**
     * Convert Page to PagedModel with HATEOAS links.
     * 
     * @param <T> Entity type
     * @param <D> DTO type
     * @param page Page of entities from repository
     * @param mapper Function to convert entity to DTO
     * @return PagedModel with HATEOAS links
     */
    @SuppressWarnings("unchecked")
    public <T, D> PagedModel<EntityModel<D>> toModel(
            Page<T> page,
            java.util.function.Function<T, D> mapper) {
        
        // Transform page content
        java.util.List<D> dtos = page.getContent()
            .stream()
            .map(mapper)
            .toList();
        
        // Create new page with DTOs
        Page<Object> objectPage = (Page<Object>) (Page<?>) new PageImpl<>(dtos, page.getPageable(), page.getTotalElements());
        
        return (PagedModel<EntityModel<D>>) (PagedModel<?>) assembler.toModel(objectPage);
    }
    
    /**
     * Convert Page to PagedModel with HATEOAS links (no transformation).
     * 
     * @param <T> DTO type
     * @param page Page of DTOs
     * @return PagedModel with HATEOAS links
     */
    @SuppressWarnings("unchecked")
    public <T> PagedModel<EntityModel<T>> toModel(Page<T> page) {
        Page<Object> objectPage = (Page<Object>) (Page<?>) page;
        
        return (PagedModel<EntityModel<T>>) (PagedModel<?>) assembler.toModel(objectPage);
    }
}
