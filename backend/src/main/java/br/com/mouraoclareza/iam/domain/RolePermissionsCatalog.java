package br.com.mouraoclareza.iam.domain;

import java.util.Map;
import java.util.Set;

/**
 * Catálogo canônico de permissões RBAC por papel operacional (compatível com server/auth-context.mjs).
 */
public final class RolePermissionsCatalog {

    private static final Set<String> ALL_PERMISSIONS = Set.of(
            "context.read",
            "company.read",
            "branch.read",
            "financial.read",
            "financial.write",
            "financial.settle",
            "financial.cancel",
            "catalog.read",
            "catalog.write",
            "bank.read",
            "bank.write",
            "reconciliation.read",
            "reconciliation.write",
            "accounting.read",
            "accounting.write",
            "audit.read"
    );

    private static final Map<String, Set<String>> ROLE_MAP = Map.of(
            "OWNER", ALL_PERMISSIONS,
            "ADMIN", ALL_PERMISSIONS,
            "FINANCE_MANAGER", Set.of(
                    "context.read", "company.read", "branch.read",
                    "financial.read", "financial.write", "financial.settle", "financial.cancel",
                    "catalog.read", "catalog.write",
                    "bank.read", "bank.write",
                    "reconciliation.read", "reconciliation.write",
                    "accounting.read", "audit.read"
            ),
            "ANALYST", Set.of(
                    "context.read", "company.read", "branch.read",
                    "financial.read", "financial.write", "financial.settle",
                    "catalog.read", "catalog.write",
                    "bank.read", "bank.write",
                    "reconciliation.read", "reconciliation.write"
            ),
            "ACCOUNTANT", Set.of(
                    "context.read", "company.read", "branch.read",
                    "financial.read", "catalog.read", "bank.read", "reconciliation.read",
                    "accounting.read", "accounting.write", "audit.read"
            ),
            "AUDITOR", Set.of(
                    "context.read", "company.read", "branch.read",
                    "financial.read", "catalog.read", "bank.read", "reconciliation.read",
                    "accounting.read", "audit.read"
            ),
            "VIEWER", Set.of(
                    "context.read", "company.read", "branch.read",
                    "financial.read", "catalog.read", "bank.read", "reconciliation.read"
            )
    );

    private RolePermissionsCatalog() {
    }

    public static Set<String> permissionsForRole(String roleCode) {
        if (roleCode == null) {
            return Set.of();
        }
        return ROLE_MAP.getOrDefault(roleCode.trim().toUpperCase(), Set.of("context.read"));
    }
}
