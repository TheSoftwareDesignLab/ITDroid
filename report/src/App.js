import { useEffect, useState } from 'react'
import Container from 'react-bootstrap/Container'
import ComparingList from './components/comparingList/ComparingList'
import Summary from './components/summary/Summary'
import HCSSummary from './components/hcsSummary/HCSSummary'
import RTLResult from './components/rtlResult/RTLResult'

function App() {
  const [report, setReport] = useState(null)
  useEffect(() => {
    fetch(`./results/report.json`)
      .then((res) => res.json())
      .then((res) => {
        setReport(res)
        
      })
  }, [])

  const [hcsTranslated, setTranslated] = useState(null)
  useEffect(() => {
    fetch(`./results/hcsTranslated.json`)
      .then((res) => res.json())
      .then((res) => {
        setTranslated(res)
        
      })
  }, [])

  const [hcsNotReplaced, setNotReplaced] = useState(null)
  useEffect(() => {
    fetch(`./results/hcsNotReplaced.json`)
      .then((res) => res.json())
      .then((res) => {
        setNotReplaced(res)
        
      })
  }, [])

  const [hcsLayout, setHcsLayout] = useState(null)
  useEffect(() => {
    fetch(`./results/hcsLayout.json`)
      .then((res) => res.json())
      .then((res) => {
        setHcsLayout(res)
        
      })
  }, [])

  const [infoRTL, setInfoRTL] = useState(null)
  useEffect(() => {
    fetch(`./results/infoRTL.json`)
      .then((res) => res.json())
      .then((res) => {
        console.log(res)
        setInfoRTL(res)

      })
  }, [])

  return (
    <Container fluid>
      <h1 className="text-center">Test Report</h1>
      {report && <Summary report={report} />}
      {hcsTranslated && hcsNotReplaced && hcsLayout && 
      <HCSSummary hcsTranslated={hcsTranslated}
                  hcsNotReplaced={hcsNotReplaced}
                  hcsLayout={hcsLayout}/>}
      {infoRTL && <RTLResult infoRTL={infoRTL}/>}
      {report && <ComparingList report={report} />}
    </Container>
  )
}

export default App
