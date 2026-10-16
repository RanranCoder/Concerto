import { expect, test } from "@playwright/test";

test("administrator creates a role, edits a draft and publishes after reviewing the change", async ({
  page,
}) => {
  const user = {
    id: 1,
    username: "reviewer",
    realName: "测试管理员",
    roleCode: "DIRECTOR",
    roleLevel: 4,
    roleId: 1,
    deptId: null,
    deptName: null,
    orgId: 1,
    orgName: "测试组织",
    permissions: ["role:view", "role:manage", "role:grant", "role:assign"],
    businessPermissions: [],
    managementCapabilities: [
      "role:view",
      "role:manage",
      "role:grant",
      "role:assign",
    ],
    isOwner: true,
    authzVersion: 1,
  };
  await page.addInitScript(
    (user) =>
      localStorage.setItem(
        "pams_token",
        JSON.stringify({ state: { token: "ui-test-token", user }, version: 1 }),
      ),
    user,
  );
  let created = false;
  let published = false;
  let draft: { permission: string; scope: string }[] = [];
  await page.route("**/api/**", async (route) => {
    const req = route.request();
    const path = new URL(req.url()).pathname;
    if (!path.startsWith("/api/")) return route.continue();
    let data: unknown = [];
    if (path === "/api/auth/me") data = user;
    else if (path === "/api/orgs/current")
      data = {
        id: 1,
        name: "测试组织",
        enabledPacks: "[]",
        themeJson: null,
        terminologyJson: null,
      };
    else if (path === "/api/permissions/catalog")
      data = [
        {
          id: 1,
          code: "activity:view",
          name: "查看活动",
          module: "activity",
          supportedScopes: ["ORG"],
          grantable: true,
          reason: "",
        },
      ];
    else if (path === "/api/roles" && req.method() === "POST") {
      created = true;
      data = 42;
    } else if (path === "/api/roles")
      data = created
        ? [
            {
              id: 42,
              name: "活动协调员",
              code: "CUSTOM42",
              status: published ? "ACTIVE" : "DRAFT",
              version: published ? 1 : 0,
            },
          ]
        : [];
    else if (path === "/api/roles/42/draft") {
      for (const change of req.postDataJSON().permissionChanges) {
        draft = draft.filter((g) => g.permission !== change.permission);
        if (change.operation === "SET")
          draft.push({ permission: change.permission, scope: change.scope });
      }
      data = 1;
    } else if (path === "/api/roles/42/change-previews")
      data = {
        id: "preview-42",
        action: "PUBLISH",
        targetId: 42,
        details: { before: [], grants: draft },
        expiresInSeconds: 300,
      };
    else if (path.endsWith("/authorization-changes/preview-42/apply")) {
      published = true;
      data = { status: "APPLIED" };
    } else if (path === "/api/roles/42")
      data = {
        role: {
          id: 42,
          name: "活动协调员",
          config_version: published ? 1 : 0,
          status: published ? "ACTIVE" : "DRAFT",
        },
        published: published ? draft : [],
        draft: published
          ? null
          : {
              id: 1,
              version: draft.length ? 1 : 0,
              grants: JSON.stringify(draft),
            },
        users: [],
        history: [],
      };
    await route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({ code: 200, data }),
    });
  });
  await page.goto("/admin/permissions");
  await page.getByRole("button", { name: "新建角色", exact: true }).click();
  await page.getByLabel("角色名称", { exact: true }).fill("活动协调员");
  await page
    .getByRole("dialog")
    .locator(".ant-modal-footer .ant-btn-primary")
    .click();
  await expect(
    page.getByRole("checkbox", { name: "查看活动", exact: true }),
  ).toBeVisible();
  await page.getByRole("checkbox", { name: "查看活动", exact: true }).check();
  await page.getByRole("button", { name: "预览并发布", exact: true }).click();
  await expect(
    page.getByRole("dialog", { name: "确认授权变更" }),
  ).toContainText("activity:view");
  expect(published).toBe(false);
  await page
    .getByRole("dialog")
    .locator(".ant-modal-footer .ant-btn-primary")
    .click();
  await expect(page.getByText("变更已生效", { exact: true })).toBeVisible();
  expect(published).toBe(true);
  await expect(
    page.getByRole("button", { name: "活动协调员 · 启用", exact: true }),
  ).toBeVisible();
});
