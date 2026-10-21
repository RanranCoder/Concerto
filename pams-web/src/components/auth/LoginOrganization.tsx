import type { PublicOrg } from '@/api/org'
import GlassSelect from '@/components/glass/GlassSelect'
import '@/styles/auth.css'

/** Shared by the public login page and organization-management brand preview. */
export default function LoginOrganization({ selected, orgs = [], onSelect }: {
  selected: PublicOrg
  orgs?: PublicOrg[]
  onSelect?: (id: number) => void
}) {
  const label = selected.shortName || selected.name
  return <div className="login-org">
    <span className="login-org-mark" aria-hidden="true">{selected.logoUrl ? <img src={selected.logoUrl} alt="" /> : label.charAt(0)}</span>
    <div><span className="login-org-label">组织展示</span>{orgs.length > 1 && onSelect
      ? <GlassSelect className="login-org-select" ariaLabel="组织展示" value={selected.id}
        options={orgs.map(org => ({ value: org.id, label: org.shortName || org.name }))} onChange={onSelect} />
      : <strong>{label}</strong>}</div>
  </div>
}
