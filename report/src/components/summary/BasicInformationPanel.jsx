import React from 'react'
import Container from 'react-bootstrap/Container'
import Row from 'react-bootstrap/Row'
import Col from 'react-bootstrap/Col'
import Form from 'react-bootstrap/Form'
import { languageName } from '../../constants/enums'

const BasicInformationPanel = ({
  appName,
  emulatorName,
  defaultLanguage,
  alpha,
  hcs,
  outputFolder,
}) => {
  return (
    <Container fluid className="summary-card pt-2 pb-2 mb-4 shadow">
      <h4>Basic Information</h4>
      <Row>
        <Col xs={6}>
          <Form>
            <Form.Group as={Row}>
              <Form.Label column sm={4}>
                App Name
              </Form.Label>
              <Col sm>
                <Form.Control column type="text" value={appName} disabled />
              </Col>
            </Form.Group>
            <Form.Group as={Row}>
              <Form.Label column sm={4}>
                Emulator Name
              </Form.Label>
              <Col sm>
                <Form.Control
                  column
                  type="text"
                  value={emulatorName}
                  disabled
                />
              </Col>
            </Form.Group>
            <Form.Group as={Row}>
              <Form.Label column sm={4}>
                Output Folder
              </Form.Label>
              <Col sm>
                <Form.Control
                  column
                  type="text"
                  value={outputFolder.substring(2)}
                  disabled
                />
              </Col>
            </Form.Group>
          </Form>
        </Col>
        <Col xs>
          <Form>
            <Form.Group as={Row}>
              <Form.Label column sm={4}>
                Default Language
              </Form.Label>
              <Col sm>
                <Form.Control
                  column
                  type="text"
                  value={languageName[defaultLanguage]}
                  disabled
                />
              </Col>
            </Form.Group>
            <Form.Group as={Row}>
              <Form.Label column sm={4}>
                Alpha
              </Form.Label>
              <Col sm>
                <Form.Control column type="text" value={alpha} disabled />
              </Col>
            </Form.Group>
            <Form.Group as={Row}>
              <Form.Label column sm={4}>
                Hardcoded Strings
              </Form.Label>
              <Col sm>
                <Form.Control column type="text" value={hcs} disabled />
              </Col>
            </Form.Group>
          </Form>
        </Col>
      </Row>
    </Container>
  )
}

export default BasicInformationPanel
