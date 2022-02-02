import { useEffect, useState } from 'react'
import Container from 'react-bootstrap/Container'
import ComparingList from './components/comparingList/ComparingList'
import Summary from './components/summary/Summary'

function App() {
  const [report, setReport] = useState(null)

  useEffect(() => {
    fetch(`${process.env.REACT_APP_OUTPUT_FOLDER}/report.json`)
      .then((res) => res.json())
      .then((res) => {
        setReport(res)
      })
  }, [])

  return (
    <Container fluid>
      <h1 className="text-center">Test Report</h1>
      {report && <Summary report={report} />}
      {report && <ComparingList report={report} />}
    </Container>
  )
}

export default App
