import { useEffect, useState, type ReactNode } from 'react'
import { createRootRouteWithContext, createRoute, createRouter, Link, Navigate, Outlet, useNavigate } from '@tanstack/react-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { QueryClient } from '@tanstack/react-query'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { api, ensureCsrf, money, type Account, type AchTransfer, type Customer, type ExternalAccount, type Session, type Transfer } from './api/client'

type RouterContext = { queryClient: QueryClient }
const authQuery = { queryKey: ['session'], queryFn: () => api<Session>('/api/v1/auth/me') }

function useSession() { return useQuery(authQuery) }
function Guard({ role, children }: { role?: Session['role']; children: ReactNode }) {
  const session = useSession()
  if (session.isLoading) return <PageState label="Checking your session…" />
  if (!session.data) return <Navigate to="/login" />
  if (role && session.data.role !== role) return <Navigate to="/" />
  return children
}

function Shell() {
  const session = useSession(); const client = useQueryClient(); const navigate = useNavigate()
  useEffect(() => { void ensureCsrf() }, [])
  const logout = async () => { await api<void>('/api/v1/auth/logout', { method: 'POST' }); client.clear(); await navigate({ to: '/login' }) }
  return <div className="app-shell">
    <header className="topbar"><Link to="/" className="brand"><span className="brand-mark">L</span><span>LedgerBank<small>Financial systems sandbox</small></span></Link>
      {session.data && <nav aria-label="Primary navigation">
        {session.data.role === 'CUSTOMER' ? <><Link to="/">Overview</Link><Link to="/transfer">Move money</Link><Link to="/activity">Activity</Link><Link to="/rails">External rails</Link></> : <Link to="/ops">Operations</Link>}
        <button className="quiet" onClick={() => void logout()}>Sign out</button>
      </nav>}
    </header>
    <main><Outlet /></main>
    <footer><span>LedgerBank is an educational sandbox.</span><span>No real funds or identities.</span></footer>
  </div>
}

const root = createRootRouteWithContext<RouterContext>()({ component: Shell })

function Home() {
  return <Guard><CustomerHome /></Guard>
}
function CustomerHome() {
  const session = useSession(); if (session.data?.role === 'OPERATOR') return <Navigate to="/ops" />
  const profile = useQuery({ queryKey: ['profile'], queryFn: () => api<Customer>('/api/v1/customers/me') })
  const accounts = useQuery({ queryKey: ['accounts'], queryFn: () => api<Account[]>('/api/v1/accounts'), enabled: profile.data?.kycStatus === 'APPROVED', refetchInterval: (q) => q.state.data?.length ? false : 1500 })
  if (profile.isError) return <Welcome />
  if (profile.data?.kycStatus !== 'APPROVED') return <Welcome profile={profile.data} />
  const account = accounts.data?.[0]
  return <section className="page dashboard">
    <div className="eyebrow">PERSONAL BANKING / USD</div><h1>Good money systems<br/>start with trustworthy books.</h1>
    {!account ? <PageState label="Opening your ledger account…" /> : <>
      <div className="balance-card"><div><span>Available balance</span><strong>{money(account.availableBalanceMinor)}</strong><small>Posted {money(account.postedBalanceMinor)}</small></div><div className="account-chip"><span>{account.name}</span><b>•••• {account.accountNumber.slice(-4)}</b><small>{account.status}</small></div></div>
      <div className="action-grid"><Link to="/transfer" className="action-card"><span>01</span><h3>Internal transfer</h3><p>Move funds atomically to another LedgerBank account.</p></Link><Link to="/rails" className="action-card"><span>02</span><h3>External rails</h3><p>Simulate settlement, rejection, and returned ACH entries.</p></Link><Link to="/activity" className="action-card"><span>03</span><h3>Ledger activity</h3><p>Inspect the postings behind your displayed balance.</p></Link></div>
    </>}
  </section>
}
function Welcome({ profile }: { profile?: Customer }) { return <section className="page hero"><div><div className="eyebrow">START HERE</div><h1>Open an account built on a real ledger.</h1><p>Use synthetic details only. The identity simulator makes its decision explicit and repeatable.</p><Link to="/onboarding" className="button">{profile ? 'Continue verification' : 'Create customer profile'}</Link></div><LedgerDiagram /></section> }
function LedgerDiagram(){return <div className="ledger-visual" aria-label="Balanced journal illustration"><div><span>DEBIT</span><b>10,000</b></div><i>=</i><div><span>CREDIT</span><b>10,000</b></div><small>Every journal must balance before PostgreSQL allows it to commit.</small></div>}

const loginSchema=z.object({email:z.string().email(),password:z.string().min(12)})
function Login(){const navigate=useNavigate();const client=useQueryClient();const [error,setError]=useState('');const form=useForm<z.infer<typeof loginSchema>>({resolver:zodResolver(loginSchema),defaultValues:{email:'',password:''}});const submit=form.handleSubmit(async values=>{setError('');try{await api('/api/v1/auth/login',{method:'POST',body:JSON.stringify(values)});await client.invalidateQueries({queryKey:['session']});await navigate({to:'/'});}catch(e){setError(e instanceof Error?e.message:'Sign-in failed')}});return <AuthFrame title="Welcome back" intro="Sign in to your isolated banking sandbox."><form onSubmit={submit}><Field label="Email" error={form.formState.errors.email?.message}><input type="email" autoComplete="email" {...form.register('email')}/></Field><Field label="Password" error={form.formState.errors.password?.message}><input type="password" autoComplete="current-password" {...form.register('password')}/></Field>{error&&<Notice tone="error">{error}</Notice>}<button disabled={form.formState.isSubmitting}>Sign in</button><p className="form-note">New here? <Link to="/register">Create an account</Link></p></form></AuthFrame>}

const registerSchema=loginSchema.extend({confirm:z.string()}).refine(v=>v.password===v.confirm,{path:['confirm'],message:'Passwords must match'})
function Register(){const [sent,setSent]=useState('');const [error,setError]=useState('');const form=useForm<z.infer<typeof registerSchema>>({resolver:zodResolver(registerSchema),defaultValues:{email:'',password:'',confirm:''}});const submit=form.handleSubmit(async v=>{setError('');try{await api('/api/v1/auth/register',{method:'POST',body:JSON.stringify({email:v.email,password:v.password})});setSent(v.email)}catch(e){setError(e instanceof Error?e.message:'Registration failed')}});return <AuthFrame title="Create your sandbox login" intro="Never enter genuine personal or banking information.">{sent?<Notice tone="success">Verification sent to {sent}. Open Mailpit at localhost:8025.</Notice>:<form onSubmit={submit}><Field label="Email"><input type="email" {...form.register('email')}/></Field><Field label="Password" hint="At least 12 characters"><input type="password" {...form.register('password')}/></Field><Field label="Confirm password" error={form.formState.errors.confirm?.message}><input type="password" {...form.register('confirm')}/></Field>{error&&<Notice tone="error">{error}</Notice>}<button>Create login</button><p className="form-note"><Link to="/login">Back to sign in</Link></p></form>}</AuthFrame>}

function Verify(){const search=new URLSearchParams(location.search);const token=search.get('token');const [state,setState]=useState('Verifying your email…');useEffect(()=>{if(!token){setState('Verification token is missing.');return}api('/api/v1/auth/verify-email',{method:'POST',body:JSON.stringify({token})}).then(()=>setState('Email verified. You can now sign in.')).catch(e=>setState(e.message))},[token]);return <AuthFrame title="Email verification" intro="One-time sandbox verification."><Notice tone={state.startsWith('Email verified')?'success':'neutral'}>{state}</Notice><Link to="/login" className="button">Continue to sign in</Link></AuthFrame>}

const profileSchema=z.object({legalName:z.string().min(2),dateOfBirth:z.string().min(10),address:z.string().min(8),testIdentityCode:z.enum(['APPROVE','REJECT','REVIEW'])})
function Onboarding(){return <Guard><OnboardingForm/></Guard>}
function OnboardingForm(){const navigate=useNavigate();const client=useQueryClient();const [error,setError]=useState('');const form=useForm<z.infer<typeof profileSchema>>({resolver:zodResolver(profileSchema),defaultValues:{legalName:'Test Customer',dateOfBirth:'1990-01-01',address:'100 Synthetic Avenue, Test City',testIdentityCode:'APPROVE'}});const submit=form.handleSubmit(async v=>{setError('');try{await api('/api/v1/customers/me',{method:'PUT',body:JSON.stringify({legalName:v.legalName,dateOfBirth:v.dateOfBirth,address:v.address})});await api('/api/v1/kyc-submissions',{method:'POST',body:JSON.stringify({testIdentityCode:v.testIdentityCode})});await client.invalidateQueries();await navigate({to:'/'});}catch(e){setError(e instanceof Error?e.message:'Onboarding failed')}});return <section className="page narrow"><div className="eyebrow">CUSTOMER ONBOARDING</div><h1>Synthetic identity profile</h1><Notice tone="neutral">This form is deliberately a simulator. Never enter real identity data.</Notice><form className="panel" onSubmit={submit}><Field label="Legal name"><input {...form.register('legalName')}/></Field><Field label="Date of birth"><input type="date" {...form.register('dateOfBirth')}/></Field><Field label="Address"><textarea {...form.register('address')}/></Field><Field label="Test outcome" hint="Deterministic behavior makes failure paths reproducible."><select {...form.register('testIdentityCode')}><option value="APPROVE">Approve immediately</option><option value="REVIEW">Send to manual review</option><option value="REJECT">Reject</option></select></Field>{error&&<Notice tone="error">{error}</Notice>}<button>Submit identity check</button></form></section>}

const transferSchema=z.object({sourceAccountId:z.string().uuid(),destinationAccountNumber:z.string().regex(/^\d{12}$/),amount:z.number().positive().max(1000000)})
function TransferPage(){return <Guard><TransferForm/></Guard>}
function TransferForm(){const accounts=useQuery({queryKey:['accounts'],queryFn:()=>api<Account[]>('/api/v1/accounts')});const [result,setResult]=useState<Transfer>();const [error,setError]=useState('');const form=useForm<z.infer<typeof transferSchema>>({resolver:zodResolver(transferSchema)});useEffect(()=>{if(accounts.data?.[0])form.setValue('sourceAccountId',accounts.data[0].accountId)},[accounts.data,form]);const submit=form.handleSubmit(async v=>{setError('');try{setResult(await api<Transfer>('/api/v1/transfers',{method:'POST',headers:{'Idempotency-Key':crypto.randomUUID()},body:JSON.stringify({sourceAccountId:v.sourceAccountId,destinationAccountNumber:v.destinationAccountNumber,amountMinor:Math.round(v.amount*100)})}))}catch(e){setError(e instanceof Error?e.message:'Transfer failed')}});return <section className="page narrow"><div className="eyebrow">INTERNAL MONEY MOVEMENT</div><h1>Send USD between ledger accounts</h1>{result?<ResultCard title={result.status==='POSTED'?'Transfer posted':'Transfer processing'} detail={`${money(result.amountMinor)} · ${result.transferId}`} status={result.status}/>:<form className="panel" onSubmit={submit}><Field label="From account"><select {...form.register('sourceAccountId')}>{accounts.data?.map(a=><option value={a.accountId} key={a.accountId}>{a.name} · {money(a.availableBalanceMinor)}</option>)}</select></Field><Field label="Destination account number" error={form.formState.errors.destinationAccountNumber?.message}><input inputMode="numeric" maxLength={12} placeholder="12 digits" {...form.register('destinationAccountNumber')}/></Field><Field label="Amount in USD" error={form.formState.errors.amount?.message}><input type="number" min="0.01" step="0.01" {...form.register('amount',{valueAsNumber:true})}/></Field>{error&&<Notice tone="error">{error}</Notice>}<button>Review and send</button></form>}</section>}

function Activity(){return <Guard><ActivityView/></Guard>}
function ActivityView(){const accounts=useQuery({queryKey:['accounts'],queryFn:()=>api<Account[]>('/api/v1/accounts')});const id=accounts.data?.[0]?.accountId;const statement=useQuery({queryKey:['statement',id],queryFn:()=>api<{items:Array<{postingId:string;type:string;description:string;amountMinor:number;bookedAt:string;reference:string}>}>(`/api/v1/accounts/${id}/statement`),enabled:!!id});return <section className="page"><div className="eyebrow">AUTHORITATIVE LEDGER</div><h1>Account activity</h1><div className="table panel"><div className="table-row table-head"><span>Description</span><span>Reference</span><span>Booked</span><span>Amount</span></div>{statement.data?.items.map(line=><div className="table-row" key={line.postingId}><span><b>{line.description}</b><small>{line.type}</small></span><code>{line.reference}</code><time>{new Date(line.bookedAt).toLocaleString()}</time><strong className={line.amountMinor>=0?'positive':'negative'}>{money(line.amountMinor)}</strong></div>)}{statement.data?.items.length===0&&<PageState label="No postings yet."/>}</div></section>}

function Rails(){return <Guard><RailView/></Guard>}
function RailView(){const accounts=useQuery({queryKey:['accounts'],queryFn:()=>api<Account[]>('/api/v1/accounts')});const links=useQuery({queryKey:['external'],queryFn:()=>api<ExternalAccount[]>('/api/v1/external-accounts')});const transfers=useQuery({queryKey:['ach'],queryFn:()=>api<AchTransfer[]>('/api/v1/ach-transfers'),refetchInterval:2000});const client=useQueryClient();const [error,setError]=useState('');const link=async()=>{await api('/api/v1/external-accounts',{method:'POST',body:JSON.stringify({institutionName:'Synthetic Community Bank',last4:'4242'})});await client.invalidateQueries({queryKey:['external']})};const start=async(direction:string,scenario:string)=>{if(!accounts.data?.[0]||!links.data?.[0])return;setError('');try{await api('/api/v1/ach-transfers',{method:'POST',headers:{'Idempotency-Key':crypto.randomUUID()},body:JSON.stringify({ledgerAccountId:accounts.data[0].accountId,externalAccountId:links.data[0].externalAccountId,direction,amountMinor:2500,scenario})});await client.invalidateQueries({queryKey:['ach']})}catch(e){setError(e instanceof Error?e.message:'Rail request failed')}};return <section className="page"><div className="eyebrow">ACCELERATED ACH SIMULATOR</div><h1>Watch asynchronous money move.</h1><p className="lead">Each scenario advances in seconds but uses durable lifecycle states, holds, journals, and reversals.</p>{!links.data?.length?<button onClick={()=>void link()}>Link synthetic account •••• 4242</button>:<div className="action-grid"><button className="action-card" onClick={()=>void start('DEPOSIT','SETTLE')}><span>IN</span><h3>Deposit $25</h3><p>Submit and settle successfully.</p></button><button className="action-card" onClick={()=>void start('WITHDRAWAL','REJECT')}><span>OUT</span><h3>Rejected withdrawal</h3><p>Reserve funds, reject, then release.</p></button><button className="action-card" onClick={()=>void start('DEPOSIT','RETURN')}><span>↩</span><h3>Returned deposit</h3><p>Settle, then post a reversal.</p></button></div>}{error&&<Notice tone="error">{error}</Notice>}<div className="table panel"><div className="table-row table-head"><span>Direction</span><span>Scenario</span><span>Created</span><span>Status</span></div>{transfers.data?.map(t=><div className="table-row" key={t.achTransferId}><b>{t.direction} · {money(t.amountMinor)}</b><span>{t.scenario}</span><time>{new Date(t.createdAt).toLocaleTimeString()}</time><Status value={t.status}/></div>)}</div></section>}

function Ops(){return <Guard role="OPERATOR"><OpsView/></Guard>}
type OpsCustomer={customerId:string;userSubject:string;kycStatus:string;createdAt:string}
type TransferTrace={transfer:Transfer;timeline:Array<{stage:string;detail:string;correlationId:string;occurredAt:string}>;events:Array<{eventId:string;eventType:string;occurredAt:string;publishedAt?:string;attempts:number}>}
type JournalDetail={journal:{journalId:string;businessReference:string;journalType:string;description:string;correlationId:string;bookedAt:string};postings:Array<{postingId:string;accountId:string;direction:string;amountMinor:number;currency:string;bookedAt:string}>}
type DeadLetter={deadLetterId:string;sourceTopic:string;messageKey?:string;payload:string;receivedAt:string;redrivenAt?:string}
type SettlementBatch={batchId:string;status:string;entryCount:number;totalDepositMinor:number;totalWithdrawalMinor:number;openedAt:string;closedAt?:string;reconciledAt?:string;exceptionDetail?:string}
type RailClock={currentTime:string;running:boolean;updatedAt:string}

function OpsView(){
 const accounts=useQuery({queryKey:['ops-accounts'],queryFn:()=>api<Account[]>('/api/v1/ops/accounts')})
 const transfers=useQuery({queryKey:['ops-transfers'],queryFn:()=>api<Transfer[]>('/api/v1/ops/transfers'),refetchInterval:2000})
 const ach=useQuery({queryKey:['ops-ach'],queryFn:()=>api<AchTransfer[]>('/api/v1/ops/ach-transfers'),refetchInterval:2000})
 const customers=useQuery({queryKey:['ops-customers'],queryFn:()=>api<OpsCustomer[]>('/api/v1/ops/customers')})
 const batches=useQuery({queryKey:['settlement-batches'],queryFn:()=>api<SettlementBatch[]>('/api/v1/ops/settlement-batches'),refetchInterval:2000})
 const railClock=useQuery({queryKey:['rail-clock'],queryFn:()=>api<RailClock>('/api/v1/ops/rail-clock'),refetchInterval:2000})
 const ledgerDlt=useQuery({queryKey:['ledger-dlt'],queryFn:()=>api<DeadLetter[]>('/api/v1/ops/ledger-dead-letters')})
 const movementDlt=useQuery({queryKey:['movement-dlt'],queryFn:()=>api<DeadLetter[]>('/api/v1/ops/movement-dead-letters')})
 const [selected,setSelected]=useState<string>()
 const trace=useQuery({queryKey:['transfer-trace',selected],queryFn:()=>api<TransferTrace>('/api/v1/ops/transfers/'+selected),enabled:!!selected})
 const journalId=trace.data?.transfer.journalId
 const journal=useQuery({queryKey:['journal',journalId],queryFn:()=>api<JournalDetail>('/api/v1/ops/journals/'+journalId),enabled:!!journalId})
 const client=useQueryClient()
 const fund=async(id:string)=>{await api('/api/v1/ops/accounts/'+id+'/sandbox-funding',{method:'POST',headers:{'Idempotency-Key':crypto.randomUUID()},body:JSON.stringify({amountMinor:100000})});await client.invalidateQueries({queryKey:['ops-accounts']})}
 const status=async(id:string,next:string)=>{await api('/api/v1/ops/accounts/'+id+'/status',{method:'PATCH',body:JSON.stringify({status:next})});await client.invalidateQueries({queryKey:['ops-accounts']})}
 const review=async(id:string,decision:string)=>{await api('/api/v1/ops/customers/'+id+'/kyc-review',{method:'POST',body:JSON.stringify({decision})});await client.invalidateQueries({queryKey:['ops-customers']})}
 const redrive=async(kind:'ledger'|'movement',id:string)=>{await api('/api/v1/ops/'+kind+'-dead-letters/'+id+'/redrive',{method:'POST',headers:{'Idempotency-Key':crypto.randomUUID()}});await client.invalidateQueries({queryKey:[kind+'-dlt']})}
 const advanceClock=async()=>{await api('/api/v1/ops/rail-clock/advance',{method:'POST',body:JSON.stringify({seconds:60})});await client.invalidateQueries({queryKey:['rail-clock']});await client.invalidateQueries({queryKey:['settlement-batches']})}
 const setClock=async(running:boolean)=>{await api('/api/v1/ops/rail-clock',{method:'PATCH',body:JSON.stringify({running})});await client.invalidateQueries({queryKey:['rail-clock']})}
 const reconcileBatch=async(id:string)=>{await api('/api/v1/ops/settlement-batches/'+id+'/reconcile',{method:'POST'});await client.invalidateQueries({queryKey:['settlement-batches']})}
 const deadLetters=[...(ledgerDlt.data??[]).map(item=>({kind:'ledger' as const,item})),...(movementDlt.data??[]).map(item=>({kind:'movement' as const,item}))]
 return <section className="page">
  <div className="eyebrow">OPERATIONS CONTROL ROOM</div><h1>Trace every state to its journal.</h1>
  <div className="stats"><div><small>Customer accounts</small><b>{accounts.data?.length??0}</b></div><div><small>Internal transfers</small><b>{transfers.data?.length??0}</b></div><div><small>ACH simulations</small><b>{ach.data?.length??0}</b></div></div>
  <h2>Rail clock and settlement batches</h2>
  <div className="panel"><div className="eyebrow">SIMULATED RAIL TIME</div><h3>{railClock.data?new Date(railClock.data.currentTime).toLocaleString():'Loading clock...'}</h3><p>{railClock.data?.running?'Clock is running at wall-clock speed.':'Clock is paused for deterministic failure testing.'}</p><span><button className="small" onClick={()=>void advanceClock()}>Advance 1 minute</button> <button className="small ghost" onClick={()=>void setClock(!railClock.data?.running)}>{railClock.data?.running?'Pause clock':'Resume clock'}</button></span></div>
  <div className="table panel settlement-batches"><div className="table-row table-head"><span>Batch</span><span>Entries / totals</span><span>Opened</span><span>Status</span></div>{batches.data?.map(batch=><div className="table-row" key={batch.batchId}><code>{batch.batchId.slice(0,8)}...</code><span>{batch.entryCount} entries<small>In {money(batch.totalDepositMinor)} / Out {money(batch.totalWithdrawalMinor)}</small></span><time>{new Date(batch.openedAt).toLocaleString()}</time>{batch.status==='EXCEPTION'||batch.status==='CLOSED'?<button className="small" onClick={()=>void reconcileBatch(batch.batchId)}>Reconcile</button>:<Status value={batch.status}/>}</div>)}{batches.data?.length===0&&<PageState label="No settlement batches yet."/>}</div>
  <h2>Manual KYC queue</h2>
  <div className="table panel"><div className="table-row table-head"><span>Customer</span><span>Subject</span><span>State</span><span>Decision</span></div>{customers.data?.filter(c=>c.kycStatus==='MANUAL_REVIEW').map(c=><div className="table-row" key={c.customerId}><code>{c.customerId.slice(0,8)}...</code><code>{c.userSubject.slice(0,8)}...</code><Status value={c.kycStatus}/><span><button className="small" onClick={()=>void review(c.customerId,'APPROVE')}>Approve</button> <button className="small ghost" onClick={()=>void review(c.customerId,'REJECT')}>Reject</button></span></div>)}{customers.data?.filter(c=>c.kycStatus==='MANUAL_REVIEW').length===0&&<PageState label="No customers await review."/>}</div>
  <h2>Customer accounts</h2>
  <div className="table panel"><div className="table-row table-head"><span>Account</span><span>Owner subject</span><span>Available</span><span>Action</span></div>{accounts.data?.map(a=><div className="table-row" key={a.accountId}><b>.... {a.accountNumber.slice(-4)}</b><code>{a.ownerSubject?.slice(0,8)}...</code><strong>{money(a.availableBalanceMinor)}</strong><span><button className="small" onClick={()=>void fund(a.accountId)}>Issue $1,000</button> <button className="small ghost" onClick={()=>void status(a.accountId,a.status==='FROZEN'?'ACTIVE':'FROZEN')}>{a.status==='FROZEN'?'Unfreeze':'Freeze'}</button></span></div>)}</div>
  <h2>Transfer search and trace</h2>
  <div className="table panel"><div className="table-row table-head"><span>Transfer</span><span>Correlation</span><span>Amount</span><span>Status</span></div>{transfers.data?.map(t=><button className="table-row row-button" key={t.transferId} onClick={()=>setSelected(t.transferId)}><code>{t.transferId.slice(0,8)}...</code><code>{t.correlationId.slice(0,8)}...</code><b>{money(t.amountMinor)}</b><Status value={t.status}/></button>)}</div>
  {trace.data&&<div className="trace-grid"><article className="panel"><div className="eyebrow">CORRELATION TIMELINE</div><h3>{trace.data.transfer.transferId}</h3>{trace.data.timeline.map((entry,index)=><div className="trace-step" key={entry.stage+index}><Status value={entry.stage}/><div><b>{entry.detail}</b><small>{new Date(entry.occurredAt).toLocaleString()} / {entry.correlationId}</small></div></div>)}<div className="eyebrow">KAFKA EVENTS</div>{trace.data.events.map(event=><div className="trace-step" key={event.eventId}><Status value={event.publishedAt?'PUBLISHED':'PENDING'}/><div><b>{event.eventType}</b><small>{event.eventId} / attempts {event.attempts}</small></div></div>)}</article><article className="panel"><div className="eyebrow">BALANCED JOURNAL</div>{journal.data?<><h3>{journal.data.journal.journalType}</h3><code>{journal.data.journal.businessReference}</code>{journal.data.postings.map(p=><div className="posting" key={p.postingId}><Status value={p.direction}/><code>{p.accountId.slice(0,8)}...</code><b>{money(p.amountMinor)}</b></div>)}<p>Debits and credits: {money(journal.data.postings.filter(p=>p.direction==='DEBIT').reduce((sum,p)=>sum+p.amountMinor,0))} each</p></>:<PageState label={journalId?'Loading journal...':'No journal has posted yet.'}/>}</article></div>}
  <h2>Dead-letter queues</h2>
  <div className="table panel"><div className="table-row table-head"><span>Source</span><span>Message key</span><span>Received</span><span>Action</span></div>{deadLetters.map(({kind,item})=><div className="table-row" key={kind+item.deadLetterId}><b>{item.sourceTopic}</b><code>{item.messageKey?.slice(0,12)??'none'}</code><time>{new Date(item.receivedAt).toLocaleString()}</time>{item.redrivenAt?<Status value="REDRIVEN"/>:<button className="small" onClick={()=>void redrive(kind,item.deadLetterId)}>Redrive</button>}</div>)}{deadLetters.length===0&&<PageState label="No poison events."/>}</div>
 </section>
}

function AuthFrame({title,intro,children}:{title:string;intro:string;children:ReactNode}){return <section className="auth-page"><div className="auth-copy"><div className="eyebrow">LEDGERBANK ACCESS</div><h1>{title}</h1><p>{intro}</p><div className="security-note"><b>Designed for failure</b><span>Rotating sessions, narrow scopes, immutable audit trails.</span></div></div><div className="auth-card">{children}</div></section>}
function Field({label,hint,error,children}:{label:string;hint?:string;error?:string;children:ReactNode}){return <label className="field"><span>{label}</span>{children}{(error||hint)&&<small className={error?'field-error':''}>{error||hint}</small>}</label>}
function Notice({tone,children}:{tone:'error'|'success'|'neutral';children:ReactNode}){return <div className={`notice ${tone}`}>{children}</div>}
function Status({value}:{value:string}){return <span className={`status status-${value.toLowerCase()}`}>{value.replace('_',' ')}</span>}
function ResultCard({title,detail,status}:{title:string;detail:string;status:string}){return <div className="result-card"><Status value={status}/><h2>{title}</h2><p>{detail}</p><Link to="/activity" className="button">View ledger activity</Link></div>}
function PageState({label}:{label:string}){return <div className="page-state"><span className="spinner"/>{label}</div>}

const routes=[createRoute({getParentRoute:()=>root,path:'/',component:Home}),createRoute({getParentRoute:()=>root,path:'/login',component:Login}),createRoute({getParentRoute:()=>root,path:'/register',component:Register}),createRoute({getParentRoute:()=>root,path:'/verify-email',component:Verify}),createRoute({getParentRoute:()=>root,path:'/onboarding',component:Onboarding}),createRoute({getParentRoute:()=>root,path:'/transfer',component:TransferPage}),createRoute({getParentRoute:()=>root,path:'/activity',component:Activity}),createRoute({getParentRoute:()=>root,path:'/rails',component:Rails}),createRoute({getParentRoute:()=>root,path:'/ops',component:Ops})]
const routeTree=root.addChildren(routes)
export const router=createRouter({routeTree,context:{queryClient:undefined!}})
declare module '@tanstack/react-router'{interface Register{router:typeof router}}
