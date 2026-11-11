import { writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
export function verifyPactProvider({root,dir,prefix,network,token,docker}) {
  const name=prefix+'-pact', env=resolve(dir,'pact.env')
  writeFileSync(env,`PACT_ADMIN_TOKEN=${token}\nPROVIDER_BASE_URL=http://${prefix}-runtime:8080\n`,{mode:0o600})
  try {
    docker(['create','--name',name,'--label',`synkro.execution=${prefix}`,'--network',network,'--env-file',env,
      '-w','/pact','node:22-bookworm-slim','sh','-c','apt-get update -qq && apt-get install -y --no-install-recommends ca-certificates && npm ci && node verify.mjs'],{quiet:true})
    docker(['cp',resolve(root,'tests/pact')+'/.',name+':/pact'],{quiet:true})
    const result=docker(['start','-a',name],{quiet:true,allowFailure:true,timeout:300000})
    const output=((result.stdout||'')+(result.stderr||'')).replaceAll(token,'[REDACTED]')
    writeFileSync(resolve(root,'.test-cache/pact-verification.log'),output)
    console.log(output.split('\n').filter(line=>line.startsWith('Pact ')).join('\n'))
    if(result.status!==0)throw Error('Pact provider verification failed; sanitized diagnostic retained')
  } finally {
    const existing=docker(['inspect',name],{quiet:true,allowFailure:true})
    if(existing.status===0) {
      if(JSON.parse(existing.stdout)[0].Config.Labels?.['synkro.execution']!==prefix)throw Error('Unowned Pact container cleanup refused')
      if(docker(['rm','-fv',name],{quiet:true,allowFailure:true}).status!==0)throw Error('Pact container cleanup failed')
    }
  }
}
