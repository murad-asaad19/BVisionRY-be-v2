package com.bvisionry.membertype;

import com.bvisionry.membertype.dto.CreateMemberTypeRequest;
import com.bvisionry.membertype.dto.MemberTypeResponse;
import com.bvisionry.membertype.dto.UpdateMemberTypeRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/member-types")
@RequiredArgsConstructor
public class MemberTypeController {

    private final MemberTypeService memberTypeService;

    /**
     * Read is open to any authenticated user — dropdowns in member/assignment
     * UI need the list. Writes are super admin only.
     */
    @GetMapping
    public ResponseEntity<List<MemberTypeResponse>> list() {
        return ResponseEntity.ok(memberTypeService.list());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('SUPER_ADMIN')")
    public ResponseEntity<MemberTypeResponse> create(@Valid @RequestBody CreateMemberTypeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(memberTypeService.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('SUPER_ADMIN')")
    public ResponseEntity<MemberTypeResponse> update(@PathVariable UUID id,
                                                     @Valid @RequestBody UpdateMemberTypeRequest request) {
        return ResponseEntity.ok(memberTypeService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('SUPER_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        memberTypeService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
