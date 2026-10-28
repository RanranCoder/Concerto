import { Navigate, useParams } from 'react-router-dom'

/** Keep existing task links while using the activity's shared identity and tab navigation. */
export default function Gantt() {
  const { id } = useParams()
  return <Navigate replace to={`/activities/${id}?tab=gantt`} />
}
