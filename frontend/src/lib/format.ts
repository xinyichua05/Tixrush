const dateTime = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' })
const longDate = new Intl.DateTimeFormat(undefined, { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' })
const time = new Intl.DateTimeFormat(undefined, { hour: 'numeric', minute: '2-digit' })
const month = new Intl.DateTimeFormat(undefined, { month: 'short' })
const day = new Intl.DateTimeFormat(undefined, { day: 'numeric' })
const weekday = new Intl.DateTimeFormat(undefined, { weekday: 'short' })

/** "20 Mar 2027, 8:00 pm" in the viewer's own time zone. */
export const formatDateTime = (iso: string) => dateTime.format(new Date(iso))
/** "Sat, 20 Mar 2027" */
export const formatLongDate = (iso: string) => longDate.format(new Date(iso))
/** "8:00 pm" */
export const formatTime = (iso: string) => time.format(new Date(iso))

/** For the date badge on posters: { month: "MAR", day: "20", weekday: "Sat" } */
export const dateParts = (iso: string) => {
  const d = new Date(iso)
  return { month: month.format(d).toUpperCase(), day: day.format(d), weekday: weekday.format(d) }
}

/** Seconds -> "9:05" */
export function formatMmSs(totalSeconds: number) {
  const s = Math.max(0, Math.floor(totalSeconds))
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`
}

/** Date -> "2027-03-20T20:00", the format an <input type="datetime-local"> wants (local time). */
export function toLocalInput(date: Date) {
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`
}

const wholeRinggit = new Intl.NumberFormat('en-MY', { maximumFractionDigits: 0 })
const sen = new Intl.NumberFormat('en-MY', { minimumFractionDigits: 2, maximumFractionDigits: 2 })

/** 688 -> "RM 688", 15.5 -> "RM 15.50", 1376 -> "RM 1,376", 0 -> "Free" */
export function formatPrice(amount: number) {
  if (amount === 0) return 'Free'
  return `RM ${Number.isInteger(amount) ? wholeRinggit.format(amount) : sen.format(amount)}`
}
