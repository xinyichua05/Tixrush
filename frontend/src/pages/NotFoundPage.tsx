import { ArrowLeft } from 'lucide-react'
import { Link } from 'react-router'

export function NotFoundPage() {
  return (
    <div className="container narrow center not-found">
      <div className="big-404 grad-text">404</div>
      <h1>This show doesn't exist</h1>
      <p className="muted">The page you're looking for has left the stage.</p>
      <Link className="btn btn-primary" to="/"><ArrowLeft size={16} /> Back to concerts</Link>
    </div>
  )
}
