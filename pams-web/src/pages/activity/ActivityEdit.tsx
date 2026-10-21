import ActivityDetail from './ActivityDetail'

/** Keep existing edit links compatible while editing in the activity context. */
export default function ActivityEdit() {
  return <ActivityDetail editOnOpen />
}
