/** Leave TixRush for another site (Stripe's payment page). Its own module so tests can replace it. */
export function goTo(url: string) {
  window.location.assign(url)
}
