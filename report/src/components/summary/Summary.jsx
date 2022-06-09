import React from 'react'
import Container from 'react-bootstrap/Container'
import BasicInformationPanel from './BasicInformationPanel'
import './styles.css'
import TestInformationPanel from './TestInformationPanel'

const Summary = ({ report }) => {
  return (
    <Container className="mb-5">
      <h2>Test Summary</h2>
      <BasicInformationPanel
        appName={report.appName}
        emulatorName={report.emulatorName}
        defaultLanguage={report.dfltLang}
        alpha={report.alpha}
        hcs={report.hardcoded}
        outputFolder={report.outputFolder}
      />

      <TestInformationPanel langsReport={report.langsReport} />
    </Container>
  )
}

export default Summary
