import { get, post, put } from "./http";
export interface Grant {
  permission: string;
  scope: "ORG";
  packVersion?: number;
}
export interface CatalogPermission {
  id: number;
  code: string;
  name: string;
  module: string;
  supportedScopes: string[];
  packVersion?: number;
  grantable: boolean;
  reason: string;
}
export interface ManagedRole {
  id: number;
  code: string;
  name: string;
  description?: string;
  status: "DRAFT" | "ACTIVE" | "DISABLED" | "ARCHIVED";
  version: number;
  userCount?: number;
}
export interface RoleDetail {
  role: {
    id: number;
    name: string;
    status: ManagedRole["status"];
    config_version: number;
  };
  published: Grant[];
  draft: { id: number; version: number; grants: string } | null;
  users: { id: number; realName: string; username: string }[];
  history: { id: number; grants: string; publishedAt: string }[];
}
export interface ChangePreview {
  id: string;
  action: string;
  targetId: number;
  details: {
    before?: Grant[];
    grants?: Grant[];
    oldRoleId?: number;
    roleId?: number;
  };
  expiresInSeconds: number;
}
export const managedRoles = () => get<ManagedRole[]>("/roles");
export const roleTemplates = () =>
  get<{ code: string; name: string }[]>("/roles/templates");
export const updateRoleMetadata = (
  id: number,
  version: number,
  name: string,
  description?: string,
) => put<void>(`/roles/${id}`, { version, name, description });
export const roleCatalog = () =>
  get<CatalogPermission[]>("/permissions/catalog");
export const roleDetail = (id: number) => get<RoleDetail>(`/roles/${id}`);
export const createRole = (
  name: string,
  description?: string,
  sourceRoleId?: number,
  templateCode?: string,
) => post<number>("/roles", { name, description, sourceRoleId, templateCode });
export const saveRoleDraft = (
  id: number,
  draftVersion: number,
  grants: Grant[],
  before: Grant[],
) =>
  put<number>(`/roles/${id}/draft`, {
    draftVersion,
    permissionChanges: [
      ...before
        .filter(
          (old) => !grants.some((next) => next.permission === old.permission),
        )
        .map((g) => ({ operation: "REVOKE", permission: g.permission })),
      ...grants
        .filter(
          (next) =>
            JSON.stringify(
              before.find((old) => old.permission === next.permission),
            ) !== JSON.stringify(next),
        )
        .map((g) => ({
          operation: "SET",
          permission: g.permission,
          scope: g.scope,
        })),
    ],
  });
export const previewRole = (id: number, action: string, version: number) =>
  post<ChangePreview>(`/roles/${id}/change-previews`, { action, version });
export const applyAuthorizationChange = (id: string) =>
  post<{ status: string }>(`/orgs/current/authorization-changes/${id}/apply`);
export const previewRoleAssignment = (userId: number, roleId: number) =>
  post<ChangePreview>("/users/role-change-previews", { userId, roleId });
export const assignableRoles = () => get<ManagedRole[]>("/roles/assignable");
export const setAdministrator = (
  id: number,
  active: boolean,
  grantable: string[],
  expiresAt?: string,
  version = 0,
) =>
  put<void>(`/orgs/current/role-delegations/${id}`, {
    active,
    grantable,
    expiresAt,
    version,
  });
export const getAdministrator = (id: number) =>
  get<{
    active: boolean;
    grantable: string[];
    expiresAt?: string;
    version: number;
  }>(`/orgs/current/role-delegations/${id}`);
export interface GovernanceLog {
  id: number;
  actorId: number;
  action: string;
  targetId: number;
  createdAt: string;
}
export const governanceLogs = () =>
  get<GovernanceLog[]>("/orgs/current/audit-logs");
export interface EffectivePermissions {
  businessPermissions: string[];
  managementCapabilities: string[];
  roleId: number;
  managementSource: "OWNER" | "DELEGATED" | "NONE";
}
export const effectivePermissions = (id: number) =>
  get<EffectivePermissions>(`/users/${id}/effective-permissions`);
export const changePassword = (oldPassword: string, newPassword: string) =>
  post<void>("/users/change-password", { oldPassword, newPassword });
