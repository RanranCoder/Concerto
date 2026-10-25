/** A bounded line comparison; large replacements use a whole-block comparison without dropping text. */
export function artifactDiff(before: string, after: string): {kind:'same'|'removed'|'added';text:string}[] {
  const a=before.split('\n'),b=after.split('\n')
  let first=0,endA=a.length,endB=b.length
  while(first<endA && first<endB && a[first]===b[first]) first++
  while(endA>first && endB>first && a[endA-1]===b[endB-1]) {endA--;endB--}
  const rows:ReturnType<typeof artifactDiff>=[]
  if(first) rows.push({kind:'same',text:a.slice(0,first).join('\n')})
  if(endA>first) rows.push({kind:'removed',text:a.slice(first,endA).join('\n')})
  if(endB>first) rows.push({kind:'added',text:b.slice(first,endB).join('\n')})
  if(endA<a.length) rows.push({kind:'same',text:a.slice(endA).join('\n')})
  return rows
}
