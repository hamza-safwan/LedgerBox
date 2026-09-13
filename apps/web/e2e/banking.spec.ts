import { expect, request, test, type Page } from '@playwright/test'

type Account = {
  accountId: string
  accountNumber: string
  availableBalanceMinor: number
}

const password = 'Synthetic-Customer-123!'

async function verificationUrl(email: string): Promise<string> {
  const mailpit = await request.newContext({ baseURL: 'http://localhost:8025' })
  try {
    await expect.poll(async () => {
      const response = await mailpit.get('/api/v1/messages')
      if (!response.ok()) return false
      const body = await response.json()
      return body.messages?.some((message: { To?: Array<{ Address?: string }> }) =>
        message.To?.some((recipient) => recipient.Address === email),
      ) ?? false
    }).toBe(true)

    const messages = await (await mailpit.get('/api/v1/messages')).json()
    const summary = messages.messages.find((message: { To?: Array<{ Address?: string }> }) =>
      message.To?.some((recipient) => recipient.Address === email),
    )
    const message = await (await mailpit.get('/api/v1/message/' + summary.ID)).json()
    const content = String(message.Text ?? '') + ' ' + String(message.HTML ?? '')
    const link = content.replaceAll('&amp;', '&').match(/http:\/\/localhost:8080\/verify-email\?token=[^\s"<]+/)?.[0]
    if (!link) throw new Error('Verification link was not present for ' + email)
    return link
  } finally {
    await mailpit.dispose()
  }
}

async function accountFromBrowser(page: Page): Promise<Account | undefined> {
  return page.evaluate(async () => {
    const response = await fetch('/api/v1/accounts')
    const accounts = await response.json() as Account[]
    return accounts[0]
  })
}

async function postWithCsrf(page: Page, path: string, key: string, body: unknown) {
  return page.evaluate(async ({ path, key, body }) => {
    const csrf = document.cookie.split('; ').find((part) => part.startsWith('XSRF-TOKEN='))?.split('=')[1]
    const response = await fetch(path, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Idempotency-Key': key,
        'X-XSRF-TOKEN': decodeURIComponent(csrf ?? ''),
      },
      body: JSON.stringify(body),
    })
    return { status: response.status, body: await response.json() }
  }, { path, key, body })
}

async function registerAndOpenAccount(page: Page, email: string): Promise<Account> {
  await page.goto('/register')
  await page.getByLabel('Email').fill(email)
  await page.getByLabel(/^Password/).fill(password)
  await page.getByLabel('Confirm password').fill(password)
  await page.getByRole('button', { name: 'Create login' }).click()
  await expect(page.getByText('Verification sent to ' + email)).toBeVisible()

  await page.goto(await verificationUrl(email))
  await expect(page.getByText('Email verified. You can now sign in.')).toBeVisible()
  await page.getByRole('link', { name: 'Continue to sign in' }).click()
  await page.getByLabel('Email').fill(email)
  await page.getByLabel('Password').fill(password)
  await page.getByRole('button', { name: 'Sign in' }).click()

  await page.getByRole('link', { name: 'Create customer profile' }).click()
  await page.getByRole('button', { name: 'Submit identity check' }).click()
  await expect.poll(() => accountFromBrowser(page), { timeout: 60_000 }).not.toBeUndefined()
  await expect(page.getByText('Available balance')).toBeVisible()
  return (await accountFromBrowser(page))!
}

test('two customers can complete an atomic, traceable internal transfer', async ({ browser }) => {
  const run = Date.now()
  const senderContext = await browser.newContext()
  const recipientContext = await browser.newContext()
  const operatorContext = await browser.newContext()

    const senderPage = await senderContext.newPage()
    const recipientPage = await recipientContext.newPage()
    const operatorPage = await operatorContext.newPage()

    const sender = await registerAndOpenAccount(senderPage, 'sender-' + run + '@ledgerbank.test')
    const recipient = await registerAndOpenAccount(recipientPage, 'recipient-' + run + '@ledgerbank.test')
    expect(await senderPage.evaluate((id) => fetch('/api/v1/accounts/' + id).then((response) => response.status), recipient.accountId)).toBe(404)
    expect(await senderPage.evaluate(() => fetch('/api/v1/ops/accounts').then((response) => response.status))).toBe(403)

    await operatorPage.goto('/login')
    await operatorPage.getByLabel('Email').fill('operator@ledgerbank.test')
    await operatorPage.getByLabel('Password').fill('ChangeMe-Operator-123!')
    await operatorPage.getByRole('button', { name: 'Sign in' }).click()
    await expect(operatorPage.getByRole('heading', { name: 'Trace every state to its journal.' })).toBeVisible()
    expect(await operatorPage.evaluate(() => fetch('/api/v1/accounts').then((response) => response.status))).toBe(403)

    const senderRow = operatorPage.locator('.table-row').filter({ hasText: sender.accountNumber.slice(-4) })
    await senderRow.getByRole('button', { name: 'Issue $1,000' }).click()
    await expect(senderRow).toContainText('$1,000.00')

    await senderPage.reload()
    await expect(senderPage.getByText('$1,000.00', { exact: true })).toBeVisible()
    await senderPage.getByRole('link', { name: 'Move money' }).click()
    await senderPage.getByLabel('Destination account number').fill(recipient.accountNumber)
    await senderPage.getByLabel('Amount in USD').fill('100.00')
    expect(await senderPage.evaluate((request) => fetch('/api/v1/transfers', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify(request),
    }).then((response) => response.status), {
      sourceAccountId: sender.accountId,
      destinationAccountNumber: recipient.accountNumber,
      amountMinor: 10000,
    })).toBe(403)
    const transferResponsePromise = senderPage.waitForResponse((response) => response.url().endsWith('/api/v1/transfers') && response.request().method() === 'POST')
    await senderPage.getByRole('button', { name: 'Review and send' }).click()
    const transferResponse = await transferResponsePromise
    const created = await transferResponse.json() as { transferId: string; journalId: string }
    const idempotencyKey = await transferResponse.request().headerValue('Idempotency-Key')
    if (!idempotencyKey) throw new Error('UI transfer did not send an idempotency key')
    await expect(senderPage.getByText(/Transfer (posted|processing)/)).toBeVisible()

    const replay = await postWithCsrf(senderPage, '/api/v1/transfers', idempotencyKey, {
      sourceAccountId: sender.accountId,
      destinationAccountNumber: recipient.accountNumber,
      amountMinor: 10000,
    })
    expect(replay.status).toBe(201)
    expect(replay.body.transferId).toBe(created.transferId)
    expect(replay.body.journalId).toBe(created.journalId)
    const conflictingReplay = await postWithCsrf(senderPage, '/api/v1/transfers', idempotencyKey, {
      sourceAccountId: sender.accountId,
      destinationAccountNumber: recipient.accountNumber,
      amountMinor: 10001,
    })
    expect(conflictingReplay.status).toBe(409)

    await senderPage.getByRole('link', { name: 'View ledger activity' }).click()
    await expect(senderPage.locator('.table-row').filter({ hasText: '$100.00' })).toBeVisible()

    await recipientPage.goto('/activity')
    await expect(recipientPage.locator('.table-row').filter({ hasText: '$100.00' })).toBeVisible()

    await operatorPage.reload()
    const transferRow = operatorPage.locator('.row-button').filter({ hasText: '$100.00' }).first()
    await expect(transferRow).toBeVisible()
    await transferRow.click()
    await expect(operatorPage.getByText('CORRELATION TIMELINE')).toBeVisible()
    await expect(operatorPage.getByText('KAFKA EVENTS')).toBeVisible()
    await expect(operatorPage.getByText('transfer.posted.v1')).toBeVisible()
    await expect(operatorPage.getByText('BALANCED JOURNAL', { exact: true })).toBeVisible()
    await expect(operatorPage.getByText('Debits and credits: $100.00 each')).toBeVisible()
})

test('ACH scenarios settle, release rejected holds, and reverse returned deposits', async ({ page, browser }) => {
  await registerAndOpenAccount(page, 'rails-' + Date.now() + '@ledgerbank.test')

  await page.getByRole('link', { name: 'External rails', exact: true }).click()
  await page.getByRole('button', { name: /Link synthetic account/ }).click()
  await expect(page.getByRole('button', { name: 'Deposit $25' })).toBeVisible()

  await page.getByRole('button', { name: 'Deposit $25' }).click()
  const settled = page.locator('.table-row').filter({ hasText: 'DEPOSIT' }).filter({ hasText: 'SETTLE' })
  await expect(settled).toContainText('SETTLED', { timeout: 30_000 })

  await page.getByRole('button', { name: 'Rejected withdrawal' }).click()
  const rejected = page.locator('.table-row').filter({ hasText: 'WITHDRAWAL' }).filter({ hasText: 'REJECT' })
  await expect(rejected).toContainText('REJECTED', { timeout: 30_000 })

  await page.getByRole('button', { name: 'Returned deposit' }).click()
  const returned = page.locator('.table-row').filter({ hasText: 'DEPOSIT' }).filter({ hasText: 'RETURN' })
  await expect(returned).toContainText('RETURNED', { timeout: 30_000 })
  await expect.poll(async () => (await accountFromBrowser(page))?.availableBalanceMinor).toBe(2500)

  await page.getByRole('link', { name: 'Activity' }).click()
  await expect(page.locator('.table-row').filter({ hasText: 'Simulated ACH deposit' })).toHaveCount(2)
  await expect(page.locator('.table-row').filter({ hasText: 'Reversal of deposit:' })).toBeVisible()

  const settlementBatchIds = await page.evaluate(async () => {
    const response = await fetch('/api/v1/ach-transfers')
    const transfers = await response.json() as Array<{ settlementBatchId?: string }>
    return transfers.map((transfer) => transfer.settlementBatchId).filter((id): id is string => Boolean(id))
  })
  expect(new Set(settlementBatchIds).size).toBe(3)

  const operatorContext = await browser.newContext()
  const operator = await operatorContext.newPage()
  await operator.goto('/login')
  await operator.getByLabel('Email').fill('operator@ledgerbank.test')
  await operator.getByLabel('Password').fill('ChangeMe-Operator-123!')
  await operator.getByRole('button', { name: 'Sign in' }).click()
  await expect(operator.getByRole('heading', { name: 'Rail clock and settlement batches' })).toBeVisible()
  await expect(operator.getByRole('button', { name: 'Advance 1 minute' })).toBeVisible()
  for (const batchId of settlementBatchIds) {
    await expect(operator.locator('.settlement-batches .table-row').filter({ hasText: batchId.slice(0, 8) })).toContainText('RECONCILED')
  }
  await operatorContext.close()
})
