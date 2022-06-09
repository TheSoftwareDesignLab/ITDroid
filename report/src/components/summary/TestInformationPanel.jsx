import React from 'react'
import Container from 'react-bootstrap/Container'
import Row from 'react-bootstrap/Row'
import Col from 'react-bootstrap/Col'
import Card from 'react-bootstrap/Card'
import CanvasJSReact from '../../assets/canvasjs.react'
import { languageName } from '../../constants/enums'

let CanvasJS = CanvasJSReact.CanvasJS
let CanvasJSChart = CanvasJSReact.CanvasJSChart

const TestInformationPanel = ({ langsReport }) => {
  let defaultInfo = {}
  const amountIPFDataPoints = []
  const amountStatesDataPoints = []
  const amountTransitionsDataPoints = []
  for (const [key, value] of Object.entries(langsReport)) {
    if (!value.dflt) {
      const langName = languageName[key]

      amountIPFDataPoints.push({
        name: langName,
        y: value.amIPFs || 0,
      })

      amountStatesDataPoints.push({ label: langName, y: value.amStates })

      amountTransitionsDataPoints.push({ label: langName, y: value.amTrans })
    } else {
      defaultInfo = { amStates: value.amStates, amTrans: value.amTrans }
    }
  }

  const AmountIPFOptions = {
    animationEnabled: true,
    title: {
      text: 'IPFs Per Language',
    },
    data: [
      {
        type: 'doughnut',
        showInLegend: true,
        indexLabel: '{name}: {y}',
        yValueFormatString: '#,##0 IPFs',
        dataPoints: amountIPFDataPoints,
      },
    ],
  }

  const AmountStatesOptions = {
    animationEnabled: true,
    axisY: {
      includeZero: true,
      title: 'Number states',
      maximum: defaultInfo.amStates,
    },
    axisX: {
      title: 'Language',
    },
    title: {
      text: 'Number of Visited States',
    },
    subtitles: [
      {
        text: `Default States: ${defaultInfo.amStates}`,
        fontSize: 24,
      },
    ],
    data: [
      {
        type: 'column',
        dataPoints: amountStatesDataPoints,
      },
    ],
  }

  const AmountTransitionsOptions = {
    animationEnabled: true,
    axisY: {
      includeZero: true,
      title: 'Number transitions',
      maximum: defaultInfo.amTrans,
    },
    axisX: {
      title: 'Language',
    },
    title: {
      text: 'Number of Transitions',
    },
    subtitles: [
      {
        text: `Default Transitions: ${defaultInfo.amTrans}`,
        fontSize: 24,
      },
    ],
    data: [
      {
        type: 'column',
        dataPoints: amountTransitionsDataPoints,
      },
    ],
  }

  return (
    <Container fluid className="summary-card pt-2 pb-2 shadow">

      <Row>
        <Col sm={6} className="mb-4">
          <Card>
            <CanvasJSChart options={AmountIPFOptions} />
          </Card>
        </Col>
        <Col sm={6} className="mb-4">
          <Card>
            <CanvasJSChart options={AmountStatesOptions} />
          </Card>
        </Col>
        <Col sm={6}>
          <Card>
            <CanvasJSChart options={AmountTransitionsOptions} />
          </Card>
        </Col>
      </Row>
    </Container>
  )
}

export default TestInformationPanel
