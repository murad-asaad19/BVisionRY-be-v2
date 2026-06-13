package com.bvisionry.membertype;

import com.bvisionry.auth.UserRepository;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.DuplicateResourceException;
import com.bvisionry.common.exception.IllegalOperationException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.membertype.dto.CreateMemberTypeRequest;
import com.bvisionry.membertype.dto.MemberTypeResponse;
import com.bvisionry.membertype.dto.UpdateMemberTypeRequest;
import com.bvisionry.membertype.entity.MemberType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MemberTypeService {

    private final MemberTypeRepository memberTypeRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<MemberTypeResponse> list() {
        return memberTypeRepository.findAllByOrderByDisplayOrderAscLabelAsc().stream()
                .map(MemberTypeResponse::from)
                .toList();
    }

    @Transactional
    public MemberTypeResponse create(CreateMemberTypeRequest request) {
        String code = request.code().trim().toUpperCase();
        if (memberTypeRepository.existsByCode(code)) {
            throw new DuplicateResourceException("Member type with code " + code + " already exists");
        }
        MemberType type = new MemberType();
        type.setCode(code);
        type.setLabel(request.label().trim());
        type.setDisplayOrder(request.displayOrder() != null ? request.displayOrder() : nextDisplayOrder());
        type.setSystem(false);
        return MemberTypeResponse.from(memberTypeRepository.save(type));
    }

    @Transactional
    public MemberTypeResponse update(UUID id, UpdateMemberTypeRequest request) {
        MemberType type = findOrThrow(id);
        if (request.label() != null) {
            String trimmed = request.label().trim();
            if (trimmed.isEmpty()) {
                throw new BadRequestException("Label cannot be blank");
            }
            type.setLabel(trimmed);
        }
        if (request.displayOrder() != null) {
            type.setDisplayOrder(request.displayOrder());
        }
        return MemberTypeResponse.from(memberTypeRepository.save(type));
    }

    @Transactional
    public void delete(UUID id) {
        MemberType type = findOrThrow(id);
        if (type.isSystem()) {
            throw new IllegalOperationException(
                    "Cannot delete the system member type '" + type.getCode()
                            + "'. System types may be renamed but not removed.");
        }
        long inUse = userRepository.countByUserType(type.getCode());
        if (inUse > 0) {
            throw new IllegalOperationException(
                    "Cannot delete member type '" + type.getCode()
                            + "' — it is currently assigned to " + inUse
                            + " member" + (inUse == 1 ? "" : "s") + ". Reassign them first.");
        }
        memberTypeRepository.delete(type);
    }

    /**
     * Throws if {@code code} is not a known member type. Used by callers that
     * accept a code from the outside world (user update, assignment creation)
     * so we fail fast rather than writing an orphan reference.
     */
    @Transactional(readOnly = true)
    public void requireExists(String code) {
        if (code == null) return;
        if (!memberTypeRepository.existsByCode(code)) {
            throw new BadRequestException("Unknown member type: " + code);
        }
    }

    private MemberType findOrThrow(UUID id) {
        return memberTypeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("MemberType", id.toString()));
    }

    private int nextDisplayOrder() {
        return memberTypeRepository.findMaxDisplayOrder().orElse(-1) + 1;
    }
}
